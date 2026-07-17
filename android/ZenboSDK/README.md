# ASUS Zenbo SDK prerequisite

ASUS Zenbo SDK binaries are proprietary vendor dependencies and are not
distributed by this repository. The repository's Apache-2.0 license does not
grant a license to redistribute the ASUS SDK.

To build the Android application:

1. Sign in to the official ASUS Zenbo developer portal.
2. Review and accept the applicable ASUS SDK end-user license agreement.
3. Download the SDK version that is compatible with the target robot and its
   firmware. For an original Zenbo/Zenbo-K, do not assume that a Zenbo Junior
   or Zenbo K2 SDK is binary-compatible.
4. Place the compatible SDK JAR at:

   ```text
   android/ZenboSDK/.local/ZenboJuniorSDK.jar
   ```

The fixed local filename above is the path referenced by
`android/RobotActivityLibrary/build.gradle`. Renaming the locally downloaded
JAR to that filename does not change its license or redistribution terms.

See the [ASUS developer website tutorial](https://zenbo.asus.com/developer/documents/overview/Developer-Website-Tutorial)
for the vendor download process. Do not commit the downloaded JAR to this
repository.
