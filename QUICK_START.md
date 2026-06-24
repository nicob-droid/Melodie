# 🚀 Quick Start - Google Sign In Fix

## Qu'est-ce qui était cassé?
```
❌ Bouton "Sign In with Google" ne faisait rien
   ├─ Pas du plugin Google Services
   ├─ Pas de google-services.json
   └─ Code utilisait mauvais token (getIdToken au lieu de getAccessToken)
```

## Qu'est-ce qui a été fixé?
```
✅ Plugin Google Services ajouté
✅ Code authentification corrigé
✅ google-services.json provisoire créé
✅ Logs DEBUG détaillés ajoutés
✅ Build réussit: BUILD SUCCESSFUL
```

---

## ⚡ CE QUE VOUS DEVEZ FAIRE EN 5 MINUTES

### 1. Firebase Console
```
→ https://console.firebase.google.com/
→ Créer un projet "melodie-player"
→ Ajouter app Android (package: com.melodie.player)
```

### 2. SHA-1 de Votre Clé
```powershell
cd $env:USERPROFILE\.android
keytool -list -v -keystore debug.keystore
# Mot de passe: android
# Copier: SHA1: AA:BB:CC:DD:...
```

### 3. Télécharger google-services.json
```
Firebase Console → Télécharger google-services.json
Placer dans: C:\Development\Android\Melodie\app\google-services.json
(Remplacer le fichier de test)
```

### 4. Rebuilder
```powershell
cd C:\Development\Android\Melodie
.\gradlew.bat clean build
```

### 5. Tester
```powershell
.\gradlew.bat installDebug
# Ouvrir l'app → onglet Drive → cliquer "Sign In with Google"
```

---

## 📊 Fichiers Modifiés

| Fichier | Changement | Type |
|---------|-----------|------|
| `gradle/libs.versions.toml` | Ajouté: `gmsGoogleServices = "4.4.1"` | Config |
| `app/build.gradle.kts` | Ajouté: plugin Google Services | Config |
| `app/google-services.json` | ✨ **CRÉÉ** (test) | Config |
| `DriveViewModel.java` | Fix: `getIdToken()` → `GoogleAuthUtil.getToken()` | Code |
| `DriveViewModel.java` | Ajouté: fallback + try/catch | Code |
| `DriveFragment.java` | Meilleur message d'erreur | Code |
| `DriveRepository.java` | Ajouté: logs DEBUG détaillés | Code |

---

## 🔧 Changement Code Principal

### AVANT (Cassé):
```java
HttpRequestInitializer init = request -> {
    request.getHeaders().setAuthorization("Bearer " + account.getIdToken());
    // ❌ getIdToken() = Identity Token (INVALIDE pour Drive API)
};
```

### APRÈS (Fixé):
```java
String accessToken = GoogleAuthUtil.getToken(
    context,
    account.getEmail(),
    "oauth2:https://www.googleapis.com/auth/drive.readonly"
);
HttpRequestInitializer init = request -> {
    request.getHeaders().setAuthorization("Bearer " + accessToken);
    // ✅ AccessToken = Token d'accès (CORRECT pour Drive API)
};
```

**La différence**:
- **ID Token**: Juste pour identifier l'utilisateur (JWT)
- **Access Token**: Pour accéder aux APIs Google (OAuth 2.0)
- **Google Drive API a besoin du Access Token**

---

## 🎯 Résultat Attendu

### ❌ AVANT
```
1. Cliquer "Sign In with Google"
2. Écran de login s'ouvre ✓
3. Se connecter ✓
4. ... rien ne se passe 😞
5. Pas de dossiers affichés
```

### ✅ APRÈS
```
1. Cliquer "Sign In with Google"
2. Écran de login s'ouvre ✓
3. Se connecter ✓
4. Logo "Déconnexion" s'affiche ✓
5. Les dossiers Google Drive s'affichent 🎉
6. Vous pouvez les sélectionner et synchroniser
```

---

## 🐛 Si ça ne marche pas

### Vérifier les logs:
```powershell
adb logcat | grep "Drive"
```

### Erreurs courantes:

| Erreur | Solution |
|--------|----------|
| `GoogleAuthException` | Vérifiez google-services.json |
| `401 Unauthorized` | Access token expiré, reconnectez-vous |
| `Package name mismatch` | SHA-1 mal configuré dans Firebase |
| Aucun dossier | Vérifiez les permissions dans Google Drive |

---

## 📚 Fichiers Documentation

- **`GOOGLE_DRIVE_SETUP.md`** - Guide complet
- **`GOOGLE_SIGNIN_FIX_SUMMARY.md`** - Résumé technique
- **`QUICK_START.md`** - Ce fichier ⬅️

---

## 🎉 C'est tout!

Une fois google-services.json remplacé, tout devrait fonctionner.

**Questions?** Regardez les logs dans Logcat!

```
adb logcat | grep -E "DriveViewModel|DriveRepository|DriveFragment"
```

