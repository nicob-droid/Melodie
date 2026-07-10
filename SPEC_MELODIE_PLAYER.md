# 🎧 Melodie

## Spécification Fonctionnelle + Technique + UI/UX complète

**Version :** 1.1  
**Date :** Juin 2026  

---

# 0. Configuration du projet Android

## 0.1 Identité

Nom : **Melodie**

Package / Namespace :
```text
com.melodie.player
```

Application ID :
```text
com.melodie.player
```

Version :
- Version Name : 1.0.0
- Version Code : 1

---

## 0.2 Android Gradle Config

```gradle
minSdk = 26
targetSdk = 36
compileSdk = 36
```

Langage :
- Java 17 (OBLIGATOIRE)

Build system :
- Gradle Kotlin DSL
- Module unique : `:app`

---

## 0.3 Stack technique

UI :
- Material Design 3
- ConstraintLayout
- RecyclerView
- Navigation Component

Architecture :
- MVVM
- Repository Pattern
- Single Activity

Audio :
- Media3 / ExoPlayer

Cloud :
- Google Drive API
- Google Sign-In

DB :
- Room

Background :
- WorkManager

DI :
- Hilt

Images :
- Glide

---

## 0.4 Permissions

- READ_MEDIA_AUDIO
- INTERNET
- ACCESS_NETWORK_STATE
- FOREGROUND_SERVICE
- FOREGROUND_SERVICE_MEDIA_PLAYBACK

---

# 🎨 1. Design System

## 1.1 Couleurs principales

- Primary (Melodie Purple) → `#7C4DFF`
- Secondary (Audio Cyan) → `#00D4FF`
- Accent (Energy Green) → `#00E5A8`

---

## 1.2 Background

- Dark : `#0F0F14`
- Surface : `#1A1A22`
- Surface variant : `#232333`

---

## 1.3 Texte

- Primary : #FFFFFF
- Secondary : #B0B0C3
- Disabled : #6C6C80

---

## 1.4 États

- Playing : #00E5A8
- Pause : #7C4DFF
- Error : #FF4D6D

---

# 🏗️ 2. Architecture logicielle

```
MVVM
Repository
Single Activity
Fragments
MediaService (foreground)
```

---

# 📱 3. Maquette UI complète

---

# 🏠 3.1 Home Screen

## Structure

```
TopBar: Melodie + Search + Settings

Section: Reprendre
Section: Albums récents
Section: Playlists
Section: Favoris

Bottom Navigation
```

## UI

- Cards arrondies
- Blur album art background
- Scroll vertical

---

# 📚 3.2 Library Screen

## Tabs

- Songs
- Albums
- Artists
- Folders
- Favorites

## Songs

- Cover left
- Title / Artist
- Duration right

## Albums

- Grid 2 columns
- Cover + name

## Artists

- Avatar circle
- Name + count

## Folders

```
📱 Phone
   ├── Music
☁️ Drive
   ├── Rock
```

---

# 🔍 3.3 Search Screen

- Sticky search bar
- Live results
- Songs / Albums / Artists

---

# 🎧 3.4 Player Screen (CORE UI)

## Structure

```
Blur background (album cover)

Top:
← Back     ❤️ Favorite

Center:
Album cover (large)

Text:
Title
Artist
Album

Progress:
00:45 ——●—— 03:20

Controls:
⏮  ⏯  ⏭

Extras:
Shuffle / Repeat
```

## UX

- Swipe down = close
- Swipe left/right = next/prev
- Double tap cover = favorite

---

# 🎶 3.5 Mini Player

- Sticky bottom bar
- Cover + title
- Play/pause
- Tap = open full player

---

# 🎚️ 3.6 Equalizer

- 5 bands
- Presets
- Bass boost
- Virtualizer
- Loudness

---

# ☁️ 3.7 Google Drive Screen

- Folder tree
- Checkboxes
- Sync button
- Status indicator

---

# ⚙️ 3.8 Settings

- Audio
- Library scan
- Drive
- Cache
- Theme

---

# 🎨 4. UX / UI rules

- Material 3 design
- Rounded corners (16–28dp)
- Smooth animations
- Blur + gradient overlays
- Minimal text, icon-first UI

---

# 📐 5. Navigation

```
Home
Library
Search
Player
Settings
```

---

# 🎵 6. Audio Engine

- Media3 / ExoPlayer
- Foreground service
- Gapless playback
- Crossfade (0–10s)

---

# ☁️ 7. Google Drive

## Features

- OAuth login
- Folder selection
- Streaming audio
- Cache offline
- Background sync (WorkManager)

---

# 🗄️ 8. Database (Room)

## Song
```
id
title
artist
album
duration
path
source
cover
favorite
```

## Album
```
id
name
artist
cover
count
```

## Playlist
```
id
name
createdAt
```

## DriveFolder
```
id
driveId
name
lastSync
```

---

# 🚀 9. Performance goals

- 50 000+ songs
- Startup < 2 sec
- Search < 100 ms
- Smooth scrolling 60fps

---

# 🧠 10. UX Flow

## Local music
Home → Song → Player → Mini Player

## Drive music
Settings → Drive → Select folder → Library → Player

---

# 🎨 11. UI MAQUETTE DÉTAILLÉE

---

## 🏠 HOME

```
[Melodie] 🔍 ⚙️

▶ Continue listening (big card)

Albums récents (grid)

Playlists (horizontal)

Favorites
```

---

## 📚 LIBRARY

```
Tabs:
Songs | Albums | Artists | Folders | Favorites
```

---

## 🎧 PLAYER (FULL SCREEN)

```
Blur background

Top bar:
Back + Favorite

Center:
Album cover (animated optional)

Info:
Title / Artist / Album

Progress bar

Controls:
Prev | Play | Next

Extras:
Shuffle / Repeat
```

---

## 🎶 MINI PLAYER

```
[cover] song - artist   ▶
```

Sticky bottom.

---

## 🎚️ EQUALIZER

```
Preset ▼

Sliders:
60Hz
230Hz
910Hz
3.6kHz
14kHz

Bass boost
Virtualizer
```

---

## ☁️ DRIVE

```
Google Drive

📁 Music
   ✔ Rock
   ✔ Jazz

[ Sync ]
```

---

## ⚙️ SETTINGS

```
Audio
Library
Drive
Cache
Theme
```

---

# 🧩 12. Gradle base (génération future)

```gradle
applicationId "com.melodie.player"
minSdk 26
targetSdk 36
compileSdk 36
```

---

# 🧭 13. Vision produit

Melodie est :

> un lecteur audio Android hybride local + cloud, fluide, moderne et premium.

---