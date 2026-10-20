Simulateur logique **Digital** compatible avec les fichiers Quartus (`.qpf`, `.qsf`, `.bdf`, `.bsf`, `.vhd`,
`.tdf`, `.vwf`…), avec la bibliothèque EA et la carte DE0 virtuelle.

Chaque paquet contient son propre Java : **rien d'autre à installer**.

## Quel fichier télécharger ?

| Système | Fichier |
|---|---|
| Mac Apple Silicon (M1, M2, M3, M4…) | `DigitalQuartus-…-macOS-arm64.dmg` |
| Windows 10/11 (installateur) | `DigitalQuartus-…-Windows-x64.msi` |
| Windows 10/11 (sans installation) | `DigitalQuartus-…-Windows-x64-portable.zip` |
| Linux Debian/Ubuntu | `DigitalQuartus-…-Linux-x64.deb` |
| Autre Linux (sans installation) | `DigitalQuartus-…-Linux-x64.tar.gz` |
| N'importe quel système avec Java 17 ou plus | `DigitalQuartus-….jar` |

## Installation

### Mac (Apple Silicon)

1. Ouvrir le `.dmg` et glisser **DigitalQuartus** dans **Applications**.
2. L'application n'est pas signée par Apple : au premier lancement, macOS refuse de l'ouvrir. Au choix :
   - **Réglages Système → Confidentialité et sécurité**, en bas : *« DigitalQuartus » a été bloqué* → **Ouvrir quand
     même** ;
   - ou, si macOS indique que l'application est « endommagée », dans le Terminal :

     ```
     xattr -dr com.apple.quarantine /Applications/DigitalQuartus.app
     ```

   Il suffit de le faire une seule fois.

### Windows

- **Installateur `.msi`** : double-cliquer. Si Windows affiche *« Windows a protégé votre ordinateur »*, cliquer sur
  **Informations complémentaires → Exécuter quand même**. L'application s'installe pour l'utilisateur courant (pas
  besoin d'être administrateur) et apparaît dans le menu Démarrer et sur le bureau.
- **Version portable `.zip`** : extraire le dossier puis lancer `DigitalQuartus\DigitalQuartus.exe`.

### Linux

- **Debian/Ubuntu** : `sudo apt install ./DigitalQuartus-*-Linux-x64.deb`, puis lancer *DigitalQuartus* depuis le menu
  des applications (catégorie Éducation) ou `/opt/digitalquartus/bin/DigitalQuartus`.
- **Archive `.tar.gz`** : `tar xzf DigitalQuartus-*-Linux-x64.tar.gz` puis `./DigitalQuartus/bin/DigitalQuartus`.

### Fichier `.jar`

Avec Java 17 ou plus installé : `java -jar DigitalQuartus-*.jar`.

## Pour commencer

- **Fichier → Ouvrir un projet ou un fichier Quartus** : ouvre un `.qpf` ou un `.bdf` (la bibliothèque EA est
  cherchée dans les dossiers voisins).
- **Fichier → Afficher sur la carte DE0** : relie les entrées-sorties à une carte DE0 virtuelle d'après les numéros de
  broche.
- **Fichier → Exporter → Exporter vers Quartus (bdf)** : pour compiler et programmer la carte sur un PC avec Quartus.

Le guide pas à pas du livret de simulation est dans
[MANUEL_SIMULATION_MAC.md](https://github.com/wakadu13/QuartusMACOpenSource/blob/main/MANUEL_SIMULATION_MAC.md).
