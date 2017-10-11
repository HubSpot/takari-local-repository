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
    if (!MERCEDES_STATUS.isValid()) {
      return false;
    }

    if (metadata.getGroupId().isEmpty() || metadata.getArtifactId().isEmpty()) {
      return false;
    }

    String[] groupParts = metadata.getGroupId().split("\\.");

    Path mercedesPath = m2Dir().resolve("repository");
    for (String groupPart : groupParts) {
      mercedesPath = mercedesPath.resolve(groupPart);
    }
    mercedesPath = mercedesPath.resolve(metadata.getArtifactId()).resolve("mercedes.artifactInfo");

    File mercedesFile = mercedesPath.toFile();
    if (!mercedesFile.exists()) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("No mercedes artifact info found at path " + mercedesPath);
      }
      return true;
    } else if (!mercedesFile.isFile()) {
      LOGGER.warn("Mercedes artifact info is not a regular file at path " + mercedesPath);
      return false;
    } else if (!mercedesFile.canRead()) {
      LOGGER.warn("Mercedes artifact info is not readable at path " + mercedesPath);
      return false;
    }

    try (InputStream inputStream = Files.newInputStream(mercedesPath, StandardOpenOption.READ)) {
      Properties mercedesProperties = new Properties();
      mercedesProperties.load(inputStream);

      String s = mercedesProperties.getProperty("lastUpdateTime");
      if (s == null) {
        LOGGER.warn("Mercedes artifact info is missing lastUpdateTime at path " + mercedesPath);
        return false;
      }

      try {
        long lastUpdateTime = Long.parseLong(s);
        return lastUpdateTime > lastModified;
      } catch (NumberFormatException e) {
        LOGGER.warn("Mercedes artifact info has an invalid lastUpdateTime at path " + mercedesPath);
        return false;
      }
    } catch (IOException e) {
      LOGGER.info("Error trying to load mercedes artifact info from " + mercedesPath, e);
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
      return new MercedesStatus(mercedesPath, mercedesProperties);
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
    private final boolean valid;

    public MercedesStatus(Path mercedesPath, Properties properties) {
      this(lastUpdateSuccess(mercedesPath, properties), lastUpdateTime(mercedesPath, properties));
    }

    public MercedesStatus(boolean lastUpdateSuccess, long lastUpdateTime) {
      this.lastUpdateSuccess = lastUpdateSuccess;
      this.lastUpdateTime = lastUpdateTime;
      this.valid = lastUpdateSuccess && (System.currentTimeMillis() - lastUpdateTime) < ONE_MINUTE;
    }

    public boolean isLastUpdateSuccess() {
      return lastUpdateSuccess;
    }

    public long getLastUpdateTime() {
      return lastUpdateTime;
    }

    public boolean isValid() {
      return valid;
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
        LOGGER.warn("Mercedes file has an invalid lastUpdateTime at path " + mercedesPath);
        return 0;
      }
    }
  }
}
