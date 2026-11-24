# Signing MORPH

Android uses the signing certificate and version code to determine whether an APK can update an installed copy. The first public MORPH v1 GitHub APK used a development certificate. Migrating to a new production key requires a fresh install for users of that APK. Export files from Vault before changing certificates. Keep the same permanent key for subsequent directly distributed updates.

Create the release key once. Store it outside the repository and let keytool prompt for passwords.

```powershell
keytool -genkeypair -v -keystore "$env:USERPROFILE\morph-production.jks" -storetype JKS -alias morph-release -keyalg RSA -keysize 4096 -validity 10000
keytool -list -v -keystore "$env:USERPROFILE\morph-production.jks" -alias morph-release
```

Store two encrypted backups of the keystore in separate locations. Save the passwords and the SHA-256 certificate fingerprint in a password manager. Verify a backup with the listing command.

Set these environment variables using your secret manager, or set the same four keys in a gitignored root `keystore.properties` file. Use forward slashes in Windows paths in that file.

- `MORPH_KEYSTORE_PATH`
- `MORPH_KEYSTORE_PASSWORD`
- `MORPH_KEY_ALIAS`
- `MORPH_KEY_PASSWORD`

```powershell
.\tools\Build-Release.ps1
```

The script verifies the signature, manifest permissions, ABI, native alignment, and ART profiles before writing `build/release/production/MORPH-arm64.apk` and `SHA256SUMS.txt`. For CI or local verification, build with `-PallowDevSign=true`. The script's `-AllowDevSign` switch uses the separate `build/release/development` directory. Verify the signer fingerprint against the permanent certificate before uploading a release.
