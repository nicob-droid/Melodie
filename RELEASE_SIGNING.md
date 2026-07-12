# Signature & Build Release — Melodie

Ce guide explique comment signer et générer un build de production, sans committer de secrets.

## 1. Générer un keystore (une seule fois)

```powershell
keytool -genkey -v -keystore melodie-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias melodie
```

Conserve ce fichier `.jks` **hors du dépôt** et sauvegarde-le en lieu sûr : il est indispensable pour toute mise à jour future de l'app sur le Play Store.

## 2. Renseigner les identifiants dans `local.properties`

`local.properties` est déjà ignoré par git. Ajoute :

```properties
RELEASE_STORE_FILE=C:/chemin/vers/melodie-release.jks
RELEASE_STORE_PASSWORD=motdepasse_du_store
RELEASE_KEY_ALIAS=melodie
RELEASE_KEY_PASSWORD=motdepasse_de_la_cle
```

En CI, tu peux fournir ces mêmes clés via des **variables d'environnement** (mêmes noms) : le build les lit automatiquement en repli.

> Si `RELEASE_STORE_FILE` n'est pas défini, le build release se fait **non signé** (pratique pour valider R8 localement).

## 3. Générer les artefacts

- APK release (test/sideload) :
```powershell
.\gradlew.bat :app:assembleRelease
```

- AAB (format attendu par le Play Store) :
```powershell
.\gradlew.bat :app:bundleRelease
```

Sorties :
- APK : `app/build/outputs/apk/release/`
- AAB : `app/build/outputs/bundle/release/`

## 4. Ce qui est déjà configuré

- **R8 / minification** : `isMinifyEnabled = true`
- **Réduction des ressources** : `isShrinkResources = true`
- **Règles ProGuard** : AdMob, Room, Hilt/Dagger, WorkManager, Media3/ExoPlayer, Glide, Google API/Drive, OkHttp (voir `app/proguard-rules.pro`).
- **AdMob** : unité de production en release, unité de test en debug ; ton appareil est enregistré comme testeur.

## 5. Rappels Play Store

- Utilise **Play App Signing** (recommandé) : tu uploades l'AAB signé avec ta clé d'upload, Google gère la clé de signature finale.
- Vérifie la politique AdMob (pas de clics accidentels, bannière non collée aux contrôles).
- Le consentement RGPD (UMP) devra être ajouté avant diffusion en UE.

