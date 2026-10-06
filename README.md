# Blus

Application Android pour suivre les bus, tramways et le trafic du réseau **Naolib**
(Nantes Métropole) en temps réel.

## Fonctionnalités

- **Carte OpenStreetMap** avec les arrêts proches et la position estimée des véhicules,
  colorée par ligne (couleurs officielles Naolib issues du GTFS).
- **Prochains passages** en temps réel pour chaque arrêt, avec le retard annoncé.
- **Recherche** sur les ~1 500 arrêts du réseau.
- **Infos trafic** : perturbations réseau (travaux, incidents, déviations) via GTFS-RT Alerts.
- **Favoris** conservés sur l'appareil.
- **Mise à jour automatique** depuis les GitHub Releases, sans quitter l'application.

## Sources de données

Blus n'utilise **aucune API privée**. Tout provient des données ouvertes officielles :

| Besoin | Source | Endpoint |
|---|---|---|
| Réseau, lignes, horaires théoriques | GTFS Nantes Métropole | `data.nantesmetropole.fr/.../244400404_transports_commun_naolib_nantes_metropole_gtfs` |
| Prochains passages temps réel | GTFS-RT TripUpdate | `proxy.transport.data.gouv.fr/resource/naolib-nantes-gtfs-rt-trip-update` |
| Perturbations | GTFS-RT Alerts | `proxy.transport.data.gouv.fr/resource/naolib-nantes-gtfs-rt-alerts` |

Les flux temps réel sont relayés par le [Point d'Accès National aux données de
transport](https://transport.data.gouv.fr) du ministère des Transports. Licence :
**Licence Ouverte 2.0**.

> **À propos des positions véhicules** : le producteur publie `TripUpdate` mais
> **pas** `VehiclePosition`. Blus estime donc la position de chaque véhicule en
> l'interpolant le long du tracé GTFS entre le dernier et le prochain arrêt connus.
> Dès que le GTFS-RT exposera `VehiclePosition`, l'application utilisera les positions
> GPS exactes sans modification (le décodeur les gère déjà).

## Installation

Télécharger l'APK depuis la page [Releases](https://github.com/tear36/blus/releases),
ou :

```bash
gh release download --repo tear36/blus --pattern '*.apk'
```

Au premier lancement, Blus télécharge le GTFS (~27 Mo) et construit sa base SQLite
locale ; c'est la seule opération qui nécessite du réseau. Ensuite tout fonctionne
hors ligne.

## Mise à jour automatique

Blus interroge `https://api.github.com/repos/tear36/blus/releases/latest`, compare la
version et propose le téléchargement de l'APK. L'installation passe par le
package installer Android : sur un appareil non rooté, c'est le seul mécanisme
autorisé, et Android demande une confirmation explicite à l'utilisateur.

Vérification automatique : à l'ouverture de l'application puis toutes les 12 h.

Pour publier une version :

```bash
git tag v1.1.0 && git push origin v1.1.0
```

Le workflow `.github/workflows/release.yml` construit l'APK **release** et le publie
dans la release GitHub correspondante.

## Développement

Prérequis : JDK 17+, Android SDK (API 35).

```bash
git clone https://github.com/tear36/blus.git
cd blus
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/blus-debug-1.0.0.apk
```

Signing de la version release : fournissez un keystore via les variables
d'environnement `BLUS_KEYSTORE`, `BLUS_KEYSTORE_PASSWORD`, `BLUS_KEY_ALIAS`,
`BLUS_KEY_PASSWORD`. Sans elles, le build release produit un APK non signé
(installation manuelle possible après `apksigner`) et l'auto-update ne peut pas
remplacer une version signée différemment.

### Structure

```
app/src/main/java/fr/tear36/blus/
├── data/
│   ├── GtfsDb.kt          schéma SQLite + requêtes (arrêts, horaires, tracés)
│   ├── GtfsImporter.kt    import CSV streaming du zip GTFS (~4,7 M lignes)
│   ├── GtfsRt.kt          décodeur protobuf GTFS-RT (TripUpdate, Alert, Vehicle)
│   ├── NetworkClient.kt   téléchargement GTFS + flux temps réel
│   └── TransitRepository  assemblage temps réel + interpolation des véhicules
├── update/                vérification des releases GitHub + installation APK
└── ui/                    Compose (carte osmdroid, listes, trafic, réglages)
```

## Licence

Code : MIT. Données : Nantes Métropole, Licence Ouverte 2.0. Tuiles cartographiques :
© contributeurs OpenStreetMap, ODbL.