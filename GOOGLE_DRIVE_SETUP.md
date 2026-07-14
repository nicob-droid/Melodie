# Configuration Google Drive Sign In - Melodie Player

## 🔴 État Actuel - IMPORTANT!

Un fichier **`google-services.json` de test** a été créé pour permettre la compilation.
**Cependant**, le vrai Sign In avec Google ne fonctionnera qu'avec les bonnes credentials Firebase/Google Cloud.

## ✅ Ce qui a été Fixé

### 1. **Ajout du Plugin Google Services**
   - ✅ Ajouté à `build.gradle.kts`
   - ✅ Configuration dans `libs.versions.toml`

### 2. **Fix de l'Authentification**
   - ❌ **Avant**: Code utilisait `account.getIdToken()` (invalide pour Drive API)
   - ✅ **Maintenant**: Utilise `GoogleAuthUtil.getToken()` pour obtenir un vrai **access token**
   - ✅ Fallback sur ID token si access token échoue
   - ✅ Meilleure gestion d'erreurs avec logs DEBUG détaillés

### 3. **Logs Améliorés**
   - Les logs montrent exactement ce qui se passe à chaque étape
   - Erreurs spécifiques apparaissent dans Logcat

## 🔧 Étapes pour Configurer Correctement

### Étape 1: Créer un Projet Firebase

1. Allez sur [Firebase Console](https://console.firebase.google.com/)
2. Cliquez **"+ Ajouter un projet"**
3. Nommez-le: `melodie-player`
4. Sélectionnez: **Authentification** et **Google Sign-In**

### Étape 1 bis: Autoriser n'importe quel compte Google (IMPORTANT)

Dans **Google Cloud Console** (projet lié à Firebase) :

1. Ouvrez **Google Auth Platform / OAuth consent screen**
2. Vérifiez que le type d'application est **External** (et pas Internal)
3. Si le statut est **Testing**, ajoutez tous les comptes à autoriser dans **Test users**
4. Si vous voulez vraiment "n'importe quel compte", passez en **In production** après validation requise
5. Vérifiez qu'aucune restriction de domaine n'est active (pas de `hd_domain` / hosted domain)

Sinon, la connexion fonctionnera uniquement pour les comptes déjà autorisés (souvent votre Gmail personnel).

### Étape 2: Configurer Android dans Firebase

1. Dans Firebase, allez à **Paramètres du projet** (⚙️ en haut)
2. Cliquez sur **Ajouter une application**
3. Sélectionnez **Android**
4. Remplissez:
   - **Package name**: `com.melodie.player`
   - **SHA-1 de signature**: Voir ci-dessous

### Étape 3: Générer SHA-1 de Votre Clé

**Sur Windows PowerShell:**

```powershell
# Aller au dossier Android
cd $env:USERPROFILE\.android

# Afficher le SHA-1
keytool -list -v -keystore debug.keystore

# Mot de passe: android
```

Cherchez la ligne **SHA1:** et copiez-la (ex: `AA:BB:CC:DD:...`)

### Étape 4: Télécharger google-services.json

1. Après configuration dans Firebase, téléchargez **google-services.json**
2. Replacez-le dans: **`C:\Development\Android\Melodie\app\google-services.json`**
   - ⚠️ **Remplacez complètement** le fichier de test actuellement présent!

### Étape 5: Rebuilder

```powershell
cd C:\Development\Android\Melodie
.\gradlew.bat clean build
```

## 🔑 Comment ça Fonctionne Maintenant

### Flux de Connexion:

```
1. Utilisateur clique "Sign In with Google"
   ↓
2. Écran de login Google s'ouvre
   ↓
3. Après login, on récupère: GoogleSignInAccount
   ↓
4. DriveViewModel.handleGoogleSignInResult() est appelé
   ↓
5. Essai #1: GoogleAuthUtil.getToken()
   → Récupère un ACCESS TOKEN pour Drive API
   ↓
6. Si échoue, Fallback: Utilise l'ID token
   ↓
7. Crée un Drive.Builder avec le Bearer token
   ↓
8. Appelle listFoldersFromDrive()
   ↓
9. Affiche les dossiers dans l'app
```

## 📋 Déboguer avec Logcat

Ouvrez **Logcat** dans Android Studio et cherchez:

```bash
# Voir tous les logs Drive
adb logcat | grep "Drive"

# Logs spécifiques
adb logcat | grep "DriveViewModel"
adb logcat | grep "DriveRepository"
adb logcat | grep "DriveFragment"
```

### Messages Typiques:

✅ **Succès:**
```
D/DriveViewModel: Starting to list Drive folders...
D/DriveRepository: Found 5 folders
D/DriveRepository: Added folder: Music (abc123xyz)
```

❌ **Erreur - pas de token:**
```
E/DriveViewModel: Failed to get access token: GoogleAuthException
E/DriveViewModel: No access token or ID token available
```

❌ **Erreur - Drive API:**
```
E/DriveRepository: IOException while listing folders: 401 Unauthorized
```

## 🧪 Tester Immédiatement

Après avoir uploadé le bon `google-services.json`:

1. Compilez et installez: `.\gradlew.bat installDebug`
2. Ouvrez l'app
3. Allez à l'onglet **Drive**
4. Cliquez **"Sign In with Google"**
5. Connectez-vous
6. Si tout fonctionne → Vos dossiers Drive s'affichent! 🎉

Si ça ne marche pas → **Regardez les logs Logcat** pour l'erreur exacte

## ✅ Checklist

- [ ] Projet Firebase créé
- [ ] SHA-1 configuré dans Firebase
- [ ] google-services.json téléchargé et placé
- [ ] Build réussit: `BUILD SUCCESSFUL`
- [ ] App s'installe sans erreurs
- [ ] Bouton "Sign In with Google" visible
- [ ] Clic sur le bouton → Google login
- [ ] Après login → Dossiers affichés ✅

## 🚨 Problèmes Courants

| Problème | Solution |
|----------|----------|
| "Erreur de connexion" | Vérifiez SHA-1 dans Firebase |
| Aucun dossier affiché | Vérifiez logs Logcat pour l'erreur API |
| App crash au login | Vérifiez google-services.json dans `app/` |
| 401 Unauthorized | Access token invalid, reconnectez-vous |

## 📚 Ressources

- [Firebase Console](https://console.firebase.google.com/)
- [Google Drive API Docs](https://developers.google.com/drive/api/v3)
- [Android Google Sign-In](https://developers.google.com/identity/sign-in/android)

---

**Status**: ✅ Code fixé et prêt | ⏳ En attente de configuration Firebase
