# Sélection et Téléchargement de Pochettes d'Albums

## Vue d'ensemble

La nouvelle fonctionnalité permet aux utilisateurs de :
1. **Consulter des suggestions de pochettes en ligne** via Deezer et iTunes
2. **Sélectionner une pochette** parmi les résultats
3. **Télécharger automatiquement** la pochette choisie dans le stockage privé de l'app
4. **Accéder hors ligne** à la pochette sans dépendre du service distant

## Flux utilisateur

### Édition d'un album

1. L'utilisateur appuie sur "Modifier un album"
2. Il clique sur le bouton **"Choisir une pochette"**
3. Un dialogue apparaît avec deux options :
   - **Choisir un fichier local** → sélecteur de fichiers standard (comportement précédent)
   - **Chercher des suggestions en ligne** → recherche en ligne

### Recherche en ligne

1. L'app recherche les suggestions depuis Deezer (priorité) et iTunes
2. Les résultats sont triés par **score de correspondance** (artiste, album, édition)
3. Une grille de pochettes s'affiche avec :
   - **Image (140x140dp)**
   - **Titre de l'album**
   - **Nom de l'artiste**
   - **Source (Deezer / iTunes)**

### Sélection et téléchargement

1. L'utilisateur clique sur une pochette
2. Un toast indique "Téléchargement de la pochette…"
3. L'app télécharge l'image en arrière-plan dans `files/covers/`
4. La pochette est stockée **localement** avec le chemin `file:///...`
5. Un toast confirme "Pochette téléchargée et sauvegardée."
6. La dialogue se ferme automatiquement
7. L'aperçu affiche la pochette téléchargée

## Architecture

### Composants principaux

#### 1. **CoverImageDownloader** (nouveau)
```
app/src/main/java/com/melodie/player/util/CoverImageDownloader.java
```
- Singleton injecté par Hilt
- Télécharge les images via HTTP/HTTPS
- Génère des noms de fichiers déterministes (MD5 de l'URL)
- Stockage : `context.getFilesDir()/covers/`
- Limite : 10 MB par image
- Timeout : 10 secondes (connexion + lecture)
- Déduplication : une même URL = un seul fichier

#### 2. **AlbumEditFragment** (modifié)
- Ajoute un choix **local / en ligne**
- Appelle `CoverImageDownloader` lors de la sélection
- Affiche les résultats en grille (GridLayoutManager 2 colonnes)
- Adapte l'URI après téléchargement (`file://...`)
- Gère les erreurs réseau avec toasts

#### 3. **CoverArtFetcher** (modifié)
- Nouvelle méthode : `searchAlbumCoverCandidates()`
- Collecte les suggestions depuis Deezer + iTunes
- Retourne une `List<CoverCandidate>` avec scoring
- Déduplication et tri par score

#### 4. **MusicRepository** (modifié)
- Expose `searchAlbumCoverCandidates()` au ViewModel
- Exécute en arrière-plan sur l'executor

#### 5. **LibraryViewModel** (modifié)
- Expose `searchAlbumCoverCandidates()` au Fragment

### Layouts

#### `dialog_online_cover_picker.xml`
- Grille RecyclerView 2 colonnes
- Hauteur fixe : 360dp
- Affiche le label de recherche

#### `item_online_cover_option.xml`
- Card Material avec image (140x140dp)
- Titre (album name)
- Sous-titre (artist name)
- Badge source (provider)

## Persistence des données

### Avant (automatique)
```
URL distante → Sauvegardée directement dans BD
exemple: https://api.deezer.com/cover/album123.jpg
```

### Après (manuel + automatique)
```
URL distante
    ↓
CoverImageDownloader.downloadAndSaveCover()
    ↓
Stockage privé : files/covers/{MD5}.jpg
    ↓
URI locale : file:///data/data/com.melodie.player/files/covers/{MD5}.jpg
    ↓
Sauvegardée en BD avec userEditedCover=true
```

## Gestion des erreurs

| Erreur | Action |
|--------|--------|
| URL invalide | Toast "URL invalide pour la pochette." |
| Réseau indisponible | Toast "Erreur lors du téléchargement de la pochette." |
| Image > 10 MB | Rejet + erreur réseau |
| Timeout (10s) | Rejet + erreur réseau |
| Espace disque insuffisant | Erreur système Android |

## Nettoyage

- Méthode `CoverImageDownloader.cleanupUnusedCovers()` disponible
- Supprime les fichiers non modifiés depuis 30 jours
- À appeler périodiquement (optionnel pour v1)

## Limitations actuelles

- ✅ Téléchargement asynchrone sans bloquer l'UI
- ✅ Déduplication par URL (MD5 hash)
- ✅ Limite de taille (10 MB)
- ✅ Timeout réseau (10s)
- ⚠️ Pas de suppression automatique des anciennes pochettes
- ⚠️ Pas de cache HTTP (chaque URL = unique)

## Compatibilité

- **API minimale** : 26 (inchangée)
- **Permissions** : INTERNET (déjà déclarée)
- **Stockage** : Privé (files/covers/) - pas d'accès externe
- **Dépendances** : Aucune nouvelle dépendance

## Cas d'utilisation et exemples

### Scénario 1 : Téléchargement simple
1. Utilisateur modifie "The Dark Side of the Moon"
2. Clique sur "Chercher des suggestions en ligne"
3. L'app trouve Deezer et iTunes
4. Sélectionne la 1re pochette Deezer
5. Image téléchargée en 1-2 secondes
6. Enregistre l'album avec `file:///...files/covers/{md5hash}.jpg`

### Scénario 2 : Hors ligne après téléchargement
1. Utilisateur télécharge une pochette en ligne
2. Plus tard, même sans connexion internet
3. La pochette s'affiche normalement (stockage local)

### Scénario 3 : Même pochette, plusieurs albums
1. Album A : télécharge cover.jpg
2. Album B : même cover.jpg (même URL)
3. Stockage dédupliqué : un seul fichier, deux albums la référencent

## Logs de débogage

Tous les téléchargements sont loggés :
```
D/CoverImageDownloader: Cover downloaded successfully: /data/data/com.melodie.player/files/covers/abc123def456.jpg
D/CoverImageDownloader: Cover already cached: /data/data/...
E/CoverImageDownloader: Failed to download cover from https://...
```

## Prochaines améliorations possibles

- [ ] Nettoyer automatiquement les pochettes > 30 jours au démarrage
- [ ] Afficher une barre de progression pour les gros téléchargements
- [ ] Permettre l'annulation d'un téléchargement en cours
- [ ] Ajouter une recherche locale (stockage externe)
- [ ] Compression JPEG adaptative (qualité vs taille)

