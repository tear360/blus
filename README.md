# Blus

Application Android pour suivre les bus, tramways et le trafic du rÃ©seau **Naolib**
(Nantes MÃ©tropole) en temps rÃ©el.

## FonctionnalitÃ©s

- **Carte OpenStreetMap** avec les arrÃªts proches et la position estimÃ©e des vÃ©hicules,
  colorÃ©e par ligne (couleurs officielles Naolib issues du GTFS).
- **Prochains passages** en temps rÃ©el pour chaque arrÃªt, avec le retard annoncÃ©.
- **Recherche** sur les ~1 500 arrÃªts du rÃ©seau.
- **Infos trafic** : perturbations rÃ©seau (travaux, incidents, dÃ©viations) via GTFS-RT Alerts.
- **Favoris** conservÃ©s sur l'appareil.
- **Mise Ã  jour automatique** depuis les GitHub Releases, sans quitter l'application.

## Sources de donnÃ©es

Blus n'utilise **aucune API privÃ©e**. Tout provient des donnÃ©es ouvertes officielles :

| Besoin | Source | Endpoint |
|---|---|---|
| RÃ©seau, lignes, horaires thÃ©oriques | GTFS Nantes MÃ©tropole | `data.nantesmetropole.fr/.../244400404_transports_commun_naolib_nantes_metropole_gtfs` |
| Prochains passages temps rÃ©el | GTFS-RT TripUpdate | `proxy.transport.data.gouv.fr/resource/naolib-nantes-gtfs-rt-trip-update` |
| Perturbations | GTFS-RT Alerts | `proxy.transport.data.gouv.fr/resource/naolib-nantes-gtfs-rt-alerts` |

Les flux temps rÃ©el sont relayÃ©s par le [Point d'AccÃ¨s National aux donnÃ©es de
transport](https://transport.data.gouv.fr) du ministÃ¨re des Transports. Licence :
**Licence Ouverte 2.0**.

> **Ã€ propos des positions vÃ©hicules** : le producteur publie `TripUpdate` mais
> **pas** `VehiclePosition`. Blus estime donc la position de chaque vÃ©hicule en
> l'interpolant le long du tracÃ© GTFS entre le dernier et le prochain arrÃªt connus.
> DÃ¨s que le GTFS-RT exposera `VehiclePosition`, l'application utilisera les positions
> GPS exactes sans modification (le dÃ©codeur les gÃ¨re dÃ©jÃ ).

## Installation

TÃ©lÃ©charger l'APK depuis la page [Releases](https://github.com/tear360/blus/releases),
ou :

```bash
gh release download --repo tear360/blus --pattern '*.apk'
```

Au premier lancement, Blus tÃ©lÃ©charge le GTFS (~27 Mo) et construit sa base SQLite
locale ; c'est la seule opÃ©ration qui nÃ©cessite du rÃ©seau. Ensuite tout fonctionne
hors ligne.

## Mise Ã  jour automatique

Blus interroge `https://api.github.com/repos/tear360/blus/releases/latest`, compare la
version et propose le tÃ©lÃ©chargement de l'APK. L'installation passe par le
package installer Android : sur un appareil non rootÃ©, c'est le seul mÃ©canisme
autorisÃ©, et Android demande une confirmation explicite Ã  l'utilisateur.

VÃ©rification automatique : Ã  l'ouverture de l'application puis toutes les 12 h.

Pour publier une version :

```bash
git tag v1.1.0 && git push origin v1.1.0
```

Le workflow `.github/workflows/release.yml` construit l'APK **release** et le publie
dans la release GitHub correspondante.

## DÃ©veloppement

PrÃ©requis : JDK 17+, Android SDK (API 35).

```bash
git clone https://github.com/tear360/blus.git
cd blus
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/blus-debug-1.0.0.apk
```

Signing de la version release : fournissez un keystore via les variables
d'environnement `BLUS_KEYSTORE`, `BLUS_KEYSTORE_PASSWORD`, `BLUS_KEY_ALIAS`,
`BLUS_KEY_PASSWORD`. Sans elles, le build release produit un APK non signÃ©
(installation manuelle possible aprÃ¨s `apksigner`) et l'auto-update ne peut pas
remplacer une version signÃ©e diffÃ©remment.

### Structure

```
app/src/main/java/fr/tear360/blus/
â”œâ”€â”€ data/
â”‚   â”œâ”€â”€ GtfsDb.kt          schÃ©ma SQLite + requÃªtes (arrÃªts, horaires, tracÃ©s)
â”‚   â”œâ”€â”€ GtfsImporter.kt    import CSV streaming du zip GTFS (~4,7 M lignes)
â”‚   â”œâ”€â”€ GtfsRt.kt          dÃ©codeur protobuf GTFS-RT (TripUpdate, Alert, Vehicle)
â”‚   â”œâ”€â”€ NetworkClient.kt   tÃ©lÃ©chargement GTFS + flux temps rÃ©el
â”‚   â””â”€â”€ TransitRepository  assemblage temps rÃ©el + interpolation des vÃ©hicules
â”œâ”€â”€ update/                vÃ©rification des releases GitHub + installation APK
â””â”€â”€ ui/                    Compose (carte osmdroid, listes, trafic, rÃ©glages)
```

## Licence

Code : MIT. DonnÃ©es : Nantes MÃ©tropole, Licence Ouverte 2.0. Tuiles cartographiques :
Â© contributeurs OpenStreetMap, ODbL.