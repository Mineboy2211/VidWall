# VidWall

Mets une vidéo ou un GIF en fond d'écran animé — sur **PC (Windows)** et **Android**.

## 🖥️ VidWall PC
- Vidéo / GIF derrière les icônes du bureau, multi-écrans
- Upscale IA (Real-ESRGAN) jusqu'à la résolution de ton écran, IA 60 fps (RIFE)
- Éditeur (coupe, couleurs, boucle sans coupure), effaceur de logo
- Jour / nuit, widgets (heure, date, météo), visualiseur audio, raccourcis clavier
- Pauses intelligentes (plein écran, bureau caché, batterie), limite de fps
- Export téléphone / GIF, mises à jour automatiques

**Télécharger :** [VidWallPC.exe](https://raw.githubusercontent.com/Mineboy2211/VidWall/main/dist/VidWallPC.exe) (Windows 10/11 64 bits).

## 📱 VidWall Home (Android)
- Launcher avec ta vidéo / GIF en fond d'écran animé système
- Recherche universelle (apps, contacts, calculs, paramètres, web)
- Jour / nuit, widgets, visualiseur audio, menu d'app animé avec raccourcis
- Mises à jour automatiques

**Télécharger :** [VidWallHome.apk](https://raw.githubusercontent.com/Mineboy2211/VidWall/main/dist/VidWallHome.apk).

## Publier une mise à jour
1. Monter la version (`appVersion` dans `pc/updater.go`, `versionName`/`versionCode` dans `android/AndroidManifest.xml`).
2. Remplacer le fichier dans `dist/`.
3. Mettre à jour `updates.json` (version, notes, taille).

Les apps lisent `updates.json` au démarrage et proposent la mise à jour toute seules.
