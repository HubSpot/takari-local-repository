package io.takari.aether.localrepo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.eclipse.aether.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum MercedesHelper {
  INSTANCE;

  private static final Logger LOGGER = LoggerFactory.getLogger(TakariUpdateCheckManager.class);
  private static final MercedesStatus INVALID = new MercedesStatus(false, 0);
  private static final MercedesStatus MERCEDES_STATUS = loadMercedesStatus();

  public boolean shouldSkipUpdate(long lastModified, Metadata metadata) {
    if (!MERCEDES_STATUS.isRecentlyUpdated()) {
      return false;
    } else if (!MERCEDES_STATUS.isLastUpdateSuccess()) {
      return true;
    }

    if (metadata.getGroupId().isEmpty() || metadata.getArtifactId().isEmpty()) {
      return false;
    }

    String[] groupParts = metadata.getGroupId().split("\\.");

    Path artifactInfoPath = m2Dir().resolve("repository");
    for (String groupPart : groupParts) {
      artifactInfoPath = artifactInfoPath.resolve(groupPart);
    }
    artifactInfoPath = artifactInfoPath.resolve(metadata.getArtifactId()).resolve("mercedes.artifactInfo");

    File artifactInfoFile = artifactInfoPath.toFile();
    if (!artifactInfoFile.exists()) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("No mercedes artifact info found at path " + artifactInfoPath);
      }
      return true;
    } else if (!artifactInfoFile.isFile()) {
      LOGGER.warn("Mercedes artifact info is not a regular file at path " + artifactInfoPath);
      return false;
    } else if (!artifactInfoFile.canRead()) {
      LOGGER.warn("Mercedes artifact info is not readable at path " + artifactInfoPath);
      return false;
    }

    try (InputStream inputStream = Files.newInputStream(artifactInfoPath, StandardOpenOption.READ)) {
      Properties artifactInfo = new Properties();
      artifactInfo.load(inputStream);

      String s = artifactInfo.getProperty("lastUpdateTime");
      if (s == null) {
        LOGGER.warn("Mercedes artifact info is missing lastUpdateTime at path " + artifactInfoPath);
        return false;
      }

      try {
        long lastUpdateTime = Long.parseLong(s);
        return lastUpdateTime < lastModified;
      } catch (NumberFormatException e) {
        LOGGER.warn("Mercedes artifact info has an invalid lastUpdateTime at path " + artifactInfoPath);
        return false;
      }
    } catch (IOException e) {
      LOGGER.info("Error trying to load mercedes artifact info from " + artifactInfoPath, e);
      return false;
    }
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
