package io.takari.aether.localrepo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.eclipse.aether.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.takari.aether.localrepo.MercedesHelper.LoadUpdateTimeResult.Status;

public enum MercedesHelper {
  INSTANCE;

  private static final Logger LOGGER = LoggerFactory.getLogger(TakariUpdateCheckManager.class);
  private static final MercedesStatus INVALID = new MercedesStatus(false, 0);
  private static final MercedesStatus MERCEDES_STATUS = loadMercedesStatus();
  private static final long BUFFER = TimeUnit.MINUTES.toMillis(1);

  public boolean shouldSkipUpdate(long lastModified, Metadata metadata) {
    long start = System.currentTimeMillis();

    if (!MERCEDES_STATUS.isRecentlyUpdated()) {
      return false;
    } else if (!MERCEDES_STATUS.isLastUpdateSuccess()) {
      return true;
    }

    if (metadata.getGroupId().isEmpty() || metadata.getArtifactId().isEmpty()) {
      return false;
    }

    Path artifactDir = artifactDir(metadata);
    final Path mercedesUpdatePath;
    if (metadata.getVersion().isEmpty()) {
      mercedesUpdatePath = artifactDir.resolve("mercedes.updateInfo");
    } else {
      mercedesUpdatePath = artifactDir.resolve(metadata.getVersion()).resolve("mercedes.updateInfo");
    }

    try {
      LoadUpdateTimeResult blazarUpdateTime = loadLastUpdateTime(artifactDir.resolve("mercedes.artifactInfo"));
      LoadUpdateTimeResult lastCheckTime = loadLastUpdateTime(mercedesUpdatePath);
      if (blazarUpdateTime.getStatus() == Status.FILE_NOT_FOUND) {
        // skip if we've fetched before
        return lastCheckTime.getStatus() == Status.SUCCESS;
      } else if (blazarUpdateTime.getStatus() != Status.SUCCESS || lastCheckTime.getStatus() != Status.SUCCESS) {
        return false;
      } else {
        return blazarUpdateTime.getTimestamp() < (lastCheckTime.getTimestamp() - BUFFER);
      }
    } finally {
      writeLastUpdateTime(mercedesUpdatePath, start);
    }
  }

  private static LoadUpdateTimeResult loadLastUpdateTime(Path path) {
    File file = path.toFile();
    if (!file.exists()) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("No mercedes file found at path " + path);
      }
      return new LoadUpdateTimeResult(Status.FILE_NOT_FOUND, null);
    } else if (!file.isFile()) {
      LOGGER.warn("Mercedes file is not a regular file at path " + path);
      return new LoadUpdateTimeResult(Status.FILE_NOT_FILE, null);
    } else if (!file.canRead()) {
      LOGGER.warn("Mercedes file is not readable at path " + path);
      return new LoadUpdateTimeResult(Status.FILE_NOT_READABLE, null);
    }

    try (InputStream inputStream = Files.newInputStream(path, StandardOpenOption.READ)) {
      Properties artifactInfo = new Properties();
      artifactInfo.load(inputStream);

      String s = artifactInfo.getProperty("lastUpdateTime");
      if (s == null) {
        LOGGER.warn("Mercedes file is missing lastUpdateTime at path " + path);
        return new LoadUpdateTimeResult(Status.FILE_MISSING_PROPERTY, null);
      }

      try {
        return new LoadUpdateTimeResult(Status.SUCCESS, Long.parseLong(s));
      } catch (NumberFormatException e) {
        LOGGER.warn("Mercedes file has an invalid lastUpdateTime at path " + path);
        return new LoadUpdateTimeResult(Status.PROPERTY_NOT_PARSEABLE, null);
      }
    } catch (IOException e) {
      LOGGER.info("Error trying to load mercedes file from " + path, e);
      return new LoadUpdateTimeResult(Status.IO_EXCEPTION, null);
    }
  }

  private static void writeLastUpdateTime(Path path, long timestamp) {
    List<String> lines = Collections.singletonList("lastUpdateTime=" + timestamp);

    Path temp = null;
    try {
      temp = Files.createTempFile("mercedes-", ".tmp");
      Files.write(temp, lines, StandardCharsets.UTF_8);
      Files.createDirectories(path.getParent());
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new RuntimeException("Error writing mercedes data to path " + path, e);
    } finally {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {}
      }
    }
  }

  private static Path artifactDir(Metadata metadata) {
    String[] groupParts = metadata.getGroupId().split("\\.");

    Path artifactDir = m2Dir().resolve("repository");
    for (String groupPart : groupParts) {
      artifactDir = artifactDir.resolve(groupPart);
    }
    return artifactDir.resolve(metadata.getArtifactId());
  }

  private static MercedesStatus loadMercedesStatus() {
    Path mercedesPath = m2Dir().resolve("mercedes.properties");

    File mercedesFile = mercedesPath.toFile();
    if (!mercedesFile.exists()) {
      LOGGER.warn("No mercedes file found at path " + mercedesPath);
      return INVALID;
    } else if (!mercedesFile.isFile()) {
      LOGGER.warn("Mercedes file is not a regular file at path " + mercedesPath);
      return INVALID;
    } else if (!mercedesFile.canRead()) {
      LOGGER.warn("Mercedes file is not readable at path " + mercedesPath);
      return INVALID;
    }

    try (InputStream inputStream = Files.newInputStream(mercedesPath, StandardOpenOption.READ)) {
      Properties mercedesProperties = new Properties();
      mercedesProperties.load(inputStream);
      MercedesStatus status = new MercedesStatus(mercedesPath, mercedesProperties);
      if (status.isLastUpdateSuccess() && status.isRecentlyUpdated()) {
        LOGGER.info("Mercedes is healthy, will skip snapshot checks based on mercedes metadata");
      } else if (status.isRecentlyUpdated()) {
        LOGGER.warn("Mercedes daemon can't connect to the API, are you on the VPN?");
        LOGGER.warn("In the meantime, will skip all snapshot checks");
        LOGGER.warn("Run with -DforceUpdate=true to override");
      } else {
        LOGGER.warn("Mercedes daemon does not appear to be running, will have to hit Nexus to check for updates");
      }

      return status;
    } catch (IOException e) {
      LOGGER.warn("Error trying to load mercedes data from " + mercedesPath, e);
      return INVALID;
    }
  }

  private static Path m2Dir() {
    return Paths.get(System.getProperty("user.home"), ".m2");
  }

  public static class LoadUpdateTimeResult {
    public enum Status {
      SUCCESS,
      FILE_NOT_FOUND,
      FILE_NOT_FILE,
      FILE_NOT_READABLE,
      FILE_MISSING_PROPERTY,
      PROPERTY_NOT_PARSEABLE,
      IO_EXCEPTION
    }

    private final Status status;
    private final Long timestamp;

    public LoadUpdateTimeResult(Status status, Long timestamp) {
      this.status = status;
      this.timestamp = timestamp;
    }

    public Status getStatus() {
      return status;
    }

    public Long getTimestamp() {
      return timestamp;
    }
  }

  private static class MercedesStatus {
    private static final long ONE_MINUTE = TimeUnit.MINUTES.toMillis(1);

    private final boolean lastUpdateSuccess;
    private final long lastUpdateTime;
    private final boolean recentlyUpdated;

    public MercedesStatus(Path mercedesPath, Properties properties) {
      this(lastUpdateSuccess(mercedesPath, properties), lastUpdateTime(mercedesPath, properties));
    }

    public MercedesStatus(boolean lastUpdateSuccess, long lastUpdateTime) {
      this.lastUpdateSuccess = lastUpdateSuccess;
      this.lastUpdateTime = lastUpdateTime;
      this.recentlyUpdated = (System.currentTimeMillis() - lastUpdateTime) < ONE_MINUTE;
    }

    public boolean isLastUpdateSuccess() {
      return lastUpdateSuccess;
    }

    public long getLastUpdateTime() {
      return lastUpdateTime;
    }

    public boolean isRecentlyUpdated() {
      return recentlyUpdated;
    }

    private static boolean lastUpdateSuccess(Path mercedesPath, Properties properties) {
      String s = properties.getProperty("lastUpdateSuccess");
      if (s == null) {
        LOGGER.warn("Mercedes file is missing lastUpdateSuccess at path " + mercedesPath);
        return false;
      }

      return Boolean.parseBoolean(s);
    }

    private static long lastUpdateTime(Path mercedesPath, Properties properties) {
      String s = properties.getProperty("lastUpdateTime");
      if (s == null) {
        LOGGER.warn("Mercedes file is missing lastUpdateTime at path " + mercedesPath);
        return 0;
      }

      try {
        return Long.parseLong(s);
      } catch (NumberFormatException e) {
        LOGGER.warn("Mercedes file has an invalid lastUpdateTime at path " + mercedesPath, e);
        return 0;
      }
    }
  }
}
