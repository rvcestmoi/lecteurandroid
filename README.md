# Lecteur simple pour Android

Lecteur de musique local en français, pour Android 8.0 et versions suivantes.

- Dossier choisi via le sélecteur Android, mémorisé après fermeture et redémarrage ; sous-dossiers inclus.
- Gros boutons lecture/pause, recul de **15 secondes**, avance de **30 secondes**.
- Bouton compact **Dossier** en haut à droite, barre de progression et sélection directe d’un titre.
- Boutons **Chanson précédente** et **Chanson suivante**, distincts des sauts temporels. Ils conservent l’état lecture/pause et sont désactivés aux extrémités de la liste.
- Mode **Aléatoire** mémorisé : mélange les titres restants en gardant le titre actuel en tête, puis parcourt chaque titre une fois. Précédente revient dans cet ordre ; en fin de liste, la lecture s’arrête. Désactiver le mode rétablit l’ordre alphabétique.
- Titre sur une zone de deux lignes constante, avec points de suspension si nécessaire : toucher le titre pour le lire en entier.
- Lecture en arrière-plan, notification et commandes sur écran verrouillé.
- Dernier titre et position enregistrés toutes les trois secondes. Au retour, le lecteur se prépare en pause.
- Enchaînement automatique des titres dans l’ordre alphabétique à la fin d’un fichier.
- Android Auto : **suivant = +30 s**, **précédent = −15 s**, dans le titre courant.
- Pause au débranchement du casque et gestion de la priorité audio.

## Compiler et installer

Ouvrir le projet dans Android Studio avec le SDK Android 36 et un JDK 17 minimum (JDK 21 conseillé). Gradle 9.1.0 et Android Gradle Plugin 9.0.1 sont configurés. Aucune bibliothèque applicative externe n’est requise.

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

L’APK est généré dans `app/build/outputs/apk/debug/app-debug.apk`. Indiquer le SDK avec `ANDROID_HOME` ou `sdk.dir` dans `local.properties` (non versionné).

Au premier lancement, toucher **Dossier** en haut à droite, autoriser l’accès, puis sélectionner un titre ou toucher Lecture. Aucun accès global aux fichiers n’est demandé. Android interdit certaines racines : choisir un sous-dossier tel que `Music/Albums`.

MP3, M4A, AAC, FLAC, OGG, OPUS et WAV sont reconnus ; le décodage dépend des codecs du téléphone. Utiliser des fichiers disponibles localement. Si le dossier est déplacé, supprimé ou son accès révoqué, le sélectionner à nouveau.

## Android Auto

L’application déclare un `MediaBrowserService` et les métadonnées Android Auto. L’écran de voiture est dessiné par Android Auto ; les gros boutons personnalisés sont ceux du téléphone. Les actions suivant/précédent et avance rapide/retour rapide utilisent les sauts temporels. Le volume au volant reste celui du véhicule.

Pour un APK installé manuellement, activer le mode développeur Android Auto puis **Sources inconnues** si nécessaire. Une diffusion publique via Google Play demande les validations Android Auto de Google. Voir l’[intégration officielle](https://developer.android.com/training/cars/media/auto).

## Vérification sur téléphone et véhicule

1. Choisir un dossier avec plusieurs titres et sous-dossiers et vérifier leur lecture.
2. Fermer et rouvrir l’application, puis redémarrer le téléphone : vérifier dossier, titre et position, sans lecture automatique.
3. Vérifier les sauts aux limites du titre, les boutons chanson précédente/suivante en lecture et en pause, leur désactivation au premier/dernier titre, ainsi que la lecture écran verrouillé. Le volume se règle avec les boutons physiques du téléphone.
4. Brancher Android Auto, ouvrir la bibliothèque et lire un titre. Vérifier que suivant ajoute 30 s et précédent retire 15 s, sans changer de titre, avec les commandes au volant également.
5. Tester un appel, le débranchement du casque, un dossier vide et un accès dossier révoqué.

La compilation et l’analyse statique ne remplacent pas ces essais sur téléphone et véhicule réels.
