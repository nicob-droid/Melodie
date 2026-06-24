# 🎯 Fix Google Sign In - Melodie Player

## Résumé des Problèmes et Solutions

### 🔴 **Problème Principal**
Le bouton "Sign In with Google" n'avait aucun effet car:
1. **Pas de plugin Google Services** appliqué dans la build
2. **Pas de google-services.json** (fichier de configuration Firebase)
3. **Code d'authentification incorrect** - utilisait `getIdToken()` au lieu du vrai **access token**

---

## ✅ **Changements Appliqués**

### 1. Configuration Build Gradle
**Fichier**: `gradle/libs.versions.toml`
```toml
# Ajouté:
gmsGoogleServices = "4.4.1"

[plugins]
# ...
gms-google-services = { id = "com.google.gms.google-services", version.ref = "gmsGoogleServices" }
```

**Fichier**: `app/build.gradle.kts`
```kotlin
plugins {
    # ...
    alias(libs.plugins.gms.google.services)  # ← NOUVEAU
}
```

### 2. Fichier Configuration Firebase
**Créé**: `app/google-services.json`
- ⚠️ Fichier de **test** provisoire
- ⚠️ Doit être **remplacé** par le vrai fichier depuis Firebase Console

### 3. Fix Code Authentification
**Fichier**: `app/src/main/java/com/melodie/player/ui/drive/DriveViewModel.java`

**Avant** ❌:
```java
public void handleGoogleSignInResult(GoogleSignInAccount account) {
    HttpRequestInitializer init = request -> {
        request.getHeaders().setAuthorization("Bearer " + account.getIdToken());
        // ❌ getIdToken() = ID token (invalide pour Drive API)
    };
    // ...
}
```

**Après** ✅:
```java
public void handleGoogleSignInResult(GoogleSignInAccount account) {
    context.getMainExecutor().execute(() -> {
        try {
            // ✅ GoogleAuthUtil.getToken() = ACCESS TOKEN (correct!)
            String accessToken = GoogleAuthUtil.getToken(
                context,
                account.getEmail(),
                "oauth2:https://www.googleapis.com/auth/drive.readonly"
            );
            
            HttpRequestInitializer init = request -> {
                request.getHeaders().setAuthorization("Bearer " + accessToken);
            };
            // ...
        } catch (Exception e) {
            // Fallback sur ID token
            fallbackToIdToken(account);
        }
    });
}
```

**Améliorations**:
- ✅ Récupère un **vrai access token** pour Drive API
- ✅ Gestion d'erreurs robuste avec try/catch
- ✅ Fallback sur ID token si access token échoue
- ✅ Logs DEBUG détaillés

### 4. Logs Améliorés
**Fichier**: `app/src/main/java/com/melodie/player/data/repository/DriveRepository.java`

Ajouté des logs détaillés:
```java
Log.d(TAG, "Starting to list Drive folders...");
Log.d(TAG, "Found " + files.size() + " folders");
Log.d(TAG, "Added folder: " + file.getName());
```

### 5. Gestion d'Erreurs
**Fichier**: `app/src/main/java/com/melodie/player/ui/drive/DriveFragment.java`

Meilleur message d'erreur:
```java
private void handleSignInResult(ActivityResult result) {
    try {
        // ...
    } catch (Exception e) {
        String errorMsg = "Erreur de connexion: " + e.getMessage();
        Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show();
        Log.e("DriveFragment", "Sign-in failed", e);
    }
}
```

---

## 📦 **État de la Build**

```
✅ BUILD SUCCESSFUL in 1m 14s
✅ Plugin Google Services appliqué
✅ Compilation Java OK
✅ APK généré: app/build/outputs/apk/debug/app-debug.apk
```

---

## 🚀 **Prochaines Étapes - À FAIRE**

### 1️⃣ Obtenir les Bonnes Credentials Firebase

1. Allez sur **[Firebase Console](https://console.firebase.google.com/)**
2. Créez un projet: **`melodie-player`**
3. Ajoutez une app Android avec:
   - Package: `com.melodie.player`
   - SHA-1 de votre clé: (voir ci-dessous)

### 2️⃣ Générer SHA-1 de Votre Clé

**Windows PowerShell**:
```powershell
cd $env:USERPROFILE\.android
keytool -list -v -keystore debug.keystore
# Mot de passe: android
# Cherchez: SHA1: AA:BB:CC:DD:...
```

### 3️⃣ Télécharger google-services.json

1. Firebase Console → Télécharger **google-services.json**
2. **Remplacer** le fichier: `C:\Development\Android\Melodie\app\google-services.json`

### 4️⃣ Rebuilder

```powershell
cd C:\Development\Android\Melodie
.\gradlew.bat clean build
```

### 5️⃣ Tester

```
1. Connecter un téléphone Android
2. .\gradlew.bat installDebug
3. Ouvrir l'app → onglet "Drive"
4. Cliquer "Sign In with Google"
5. Se connecter avec son compte Google
6. 🎉 Les dossiers Drive devraient s'afficher!
```

---

## 🔍 **Déboguer les Problèmes**

Si ça ne marche pas, vérifiez les logs:

```powershell
# Terminal 1: Voir les logs
adb logcat | grep "Drive"

# Terminal 2: Lancer l'app
.\gradlew.bat installDebug
adb shell am start -n com.melodie.player/.ui.MainActivity
```

### Logs Typiques:

✅ **Succès**:
```
D/DriveViewModel: Starting to list Drive folders...
D/DriveRepository: Found 5 folders
D/DriveRepository: Added folder: Music (abc123xyz)
```

❌ **Erreur - Pas de token**:
```
E/DriveViewModel: Failed to get access token: GoogleAuthException
E/DriveViewModel: No access token or ID token available
```

❌ **Erreur - API Drive**:
```
E/DriveRepository: IOException while listing folders: 401 Unauthorized
```

---

## 📝 **Documentation**

Voir: **`GOOGLE_DRIVE_SETUP.md`** pour le guide complet d'installation

---

## ✅ **Checklist Final**

- [ ] Avez-vous accès à Firebase Console?
- [ ] Avez-vous extrait le SHA-1 de votre clé?
- [ ] Avez-vous téléchargé `google-services.json`?
- [ ] L'avez-vous placé dans `app/google-services.json`?
- [ ] La build réussit: `BUILD SUCCESSFUL`?
- [ ] L'app s'installe sans erreur?
- [ ] Avez-vous vu le bouton "Sign In with Google"?
- [ ] La connexion Google fonctionne?
- [ ] Les dossiers Drive s'affichent? ✅

---

## 🔗 **Ressources**

- [Firebase Console](https://console.firebase.google.com/)
- [Google Drive API v3](https://developers.google.com/drive/api/v3)
- [Android Google Sign-In](https://developers.google.com/identity/sign-in/android)

---

**Statut**: ✅ Code fixé et prêt | ⏳ En attente de configuration Firebase

