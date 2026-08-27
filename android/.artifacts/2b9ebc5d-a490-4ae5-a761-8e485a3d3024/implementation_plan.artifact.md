# Auto-incrementing Version Code and Name

The goal is to automatically increment the `versionCode` and `versionName` whenever a new version is built.

## Proposed Changes

### [NEW] [version.properties](file:///D:/Projetos/smartpos-pdv-pro/android/version.properties)
Create a property file to persist the versioning information.

```properties
VERSION_CODE=8
VERSION_MAJOR=1
VERSION_MINOR=8
VERSION_PATCH=1
```

### [MODIFY] [app/build.gradle](file:///D:/Projetos/smartpos-pdv-pro/android/app/build.gradle)
1.  Add logic to load the `version.properties` file at the top of the script.
2.  Update `defaultConfig` to use the dynamic values.
3.  Add an `incrementVersion` task that updates the file.

```groovy
// Example logic to be added to build.gradle
def versionPropsFile = file('../version.properties')
def Properties versionProps = new Properties()
if (versionPropsFile.exists()) {
    versionProps.load(new FileInputStream(versionPropsFile))
}

def vCode = versionProps['VERSION_CODE'].toInteger()
def vName = "${versionProps['VERSION_MAJOR']}.${versionProps['VERSION_MINOR']}.${versionProps['VERSION_PATCH']}"

// ... inside android.defaultConfig
versionCode vCode
versionName vName
```

## Verification Plan

### Manual Verification
1.  Run `./gradlew incrementVersion` (to be created) and verify `version.properties` updates.
2.  Verify that `app/build.gradle` correctly picks up the values.
