# Compatibilité Quartus

Cette version de Digital sait importer les projets **Intel/Altera Quartus** (Quartus II, Quartus Prime)
pour les **visualiser**, les **modifier** et les **simuler**, avec un rendu graphique proche de l'éditeur de
schémas (*Block Editor*) de Quartus, et sait **exporter** un circuit Digital en schéma Quartus (`.bdf`).
La couche Quartus est écrite en **Kotlin** (le reste de Digital en Java) et l'application se construit avec
**Java 17**.

## Démarrage rapide

1. Construire l'application (Java 17 ou plus récent et Maven ; Kotlin est téléchargé par Maven) :

   ```
   mvn -P no-git-rev -Dgit.commit.id.describe=quartus -DskipTests package
   java -jar target/Digital.jar
   ```

2. Dans Digital : **File → Open Quartus project or file** (*Fichier → Ouvrir un projet ou un fichier Quartus*
   si l'interface est en français : *Edit → Settings → Language*).
   Le dialogue « Ouvrir » classique accepte aussi directement les fichiers Quartus, et on peut les passer
   en argument : `java -jar Digital.jar MonProjet.qpf`.

3. Le projet est converti dans un dossier `digital/` placé à côté du fichier ouvert : un circuit `.dig`
   par bloc (schéma `.bdf`, entité VHDL, module Verilog, mégafonction…). Le circuit de plus haut niveau
   (`TOP_LEVEL_ENTITY` du `.qsf`) est ouvert. Les avertissements (sources manquantes, largeurs de bus
   incohérentes…) sont affichés à la fin de l'import.

4. Lancer la simulation (▶). Les entrées se cliquent comme dans Digital ; une broche d'entrée qui commande
   l'horloge de bascules (ou nommée `clk`, `clock`, `horloge`, `H50Meg`…) devient une horloge.

En ligne de commande, sans interface graphique :

```
java -cp Digital.jar CLI quartus -file MonProjet.qpf [-out dossier]
java -cp Digital.jar CLI quartusExport -dig MonCircuit.dig [-bdf MonCircuit.bdf]
```

## Fichiers pris en charge

| Fichier | Rôle | Prise en charge |
|---|---|---|
| `.qpf` | projet | lit `PROJECT_REVISION` pour trouver le `.qsf` |
| `.qsf` | réglages | `TOP_LEVEL_ENTITY`, fichiers du projet (`BDF_FILE`, `VHDL_FILE`, `VERILOG_FILE`, `QIP_FILE`…), `SEARCH_PATH`/`USER_LIBRARIES`, affectations de broches `set_location_assignment` (reprises comme numéros de broches) |
| `.qip` | fichiers d'IP (MegaWizard) | lus comme un `.qsf` (y compris `[file join $::quartus(qip_path) ...]`) |
| `.bdf` | schéma (*Block Design File*) | converti en schéma Digital : symboles, broches, fils, bus, étiquettes, jonctions, textes et dessins libres |
| `.bsf` | symbole (*Block Symbol File*) | utilisé pour l'interface d'un bloc quand la source est absente |
| `.mif`, `.hex` | contenu des mémoires | lus pour `LPM_FILE` / `INIT_FILE` (`LPM_ROM`, `LPM_RAM_DQ`, `ALTSYNCRAM`) ; écrits lors de l'export des ROM |
| `.vwf` | chronogramme (*Vector Waveform File*) | converti en **cas de test** Digital (voir plus bas) |
| `.vhd`, `.vhdl` | VHDL | entité simulée par **GHDL** (élément *External File* de Digital) ; les enveloppes MegaWizard de mégafonctions supportées sont simulées nativement |
| `.v`, `.sv` | Verilog | module simulé par **Icarus Verilog** ; mégafonctions comme ci-dessus |
| `.tdf` | AHDL | interface lue (`SUBDESIGN`), bloc importé vide (AHDL non simulé) |
| `.qws`, `.gdf`, `.sym` | espace de travail, anciens formats MAX+plus II | ignorés |

Les sous-blocs sont recherchés dans : les fichiers listés dans le projet, le dossier du projet et ses
sous-dossiers, les `SEARCH_PATH`/`USER_LIBRARIES`, et les dossiers voisins du projet (par exemple
`../MaBiblio`). Un bloc dont la source est introuvable est importé comme **bloc vide** (sorties à 0) en
gardant son dessin, pour que le schéma reste complet.

## Ce qui est converti

- **Géométrie** : les coordonnées Quartus (grille de 8) sont multipliées par 2,5 pour tomber sur la grille
  de 20 de Digital. Le dessin de chaque symbole et de chaque broche est repris tel quel du `.bdf`
  (lignes, rectangles, cercles, arcs, textes), d'où l'apparence identique à Quartus.
- **Connexions** : les fils sont reliés par leurs extrémités et par les points posés sur un autre fil
  (jonctions en T) ; deux fils qui se croisent ne sont pas reliés, comme dans Quartus.
- **Noms de réseaux** : les fils portant le même nom sont reliés (tunnels Digital invisibles), une broche
  nomme son réseau (`H50Meg` sur un fil relie ce fil à la broche d'entrée `H50Meg`).
- **Bus** : `a[7..0]`, `a[3]`, `a[3..0],b,GND`… Les extractions et concaténations de bits sont réalisées
  par des séparateurs (*splitters*) invisibles. `VCC`/`GND` reliés à un bus valent tous les bits à 1/0.
- **Entrées non connectées** : valeur par défaut de Quartus (`CLRN`, `PRN`, `ENA` à 1, les autres à 0,
  ou la valeur par défaut `VCC`/`GND` de la broche du sous-bloc).

### Primitives et composants simulés nativement

175 composants Quartus sont simulés nativement (sans GHDL ni Icarus Verilog), rangés comme dans le dialogue
*Symbol* de Quartus (**Components → Quartus**) :

| Catégorie | Composants |
|---|---|
| `primitives/logic` | `AND2`…`AND12`, `NAND…`, `OR…`, `NOR…`, variantes `BAND…`, `BNAND…`, `BOR…`, `BNOR…` (entrées inversées), `XOR`, `XNOR`, `NOT` |
| `primitives/buffer` | `WIRE`, `LCELL`, `GLOBAL`, `SOFT`, `CARRY`, `CASCADE`, `ROW_GLOBAL`, `ALT_INBUF`, `ALT_OUTBUF`, `CLKLOCK`, `EXP`, `TRI`, `OPNDRN` |
| `primitives/storage` | `DFF`, `DFFE`, `DFFEA`, `DFFEAS`, `TFF`, `TFFE`, `JKFF`, `JKFFE`, `SRFF`, `SRFFE`, `LATCH` (`CLRN`/`PRN` asynchrones actifs à l'état bas) |
| `primitives/other` | `VCC`, `GND` |
| `megafunctions/arithmetic` | `LPM_COUNTER`, `LPM_ADD_SUB`, `LPM_COMPARE`, `LPM_MULT`, `LPM_DIVIDE`, `LPM_ABS`, `LPM_CLSHIFT` |
| `megafunctions/gates` | `LPM_AND`, `LPM_OR`, `LPM_XOR`, `LPM_INV`, `LPM_CONSTANT`, `LPM_DECODE`, `LPM_MUX`, `BUSMUX`, `LPM_BUSTRI` |
| `megafunctions/storage` | `LPM_SHIFTREG`, `LPM_FF`, `LPM_LATCH`, `LPM_ROM`, `LPM_RAM_DQ`, `ALTSYNCRAM`, `LPM_FIFO`, `SCFIFO` |
| `megafunctions/IO` | `ALTPLL` (horloges de sortie = horloge d'entrée) |
| `others/maxplus2` | 73 circuits 74xx : portes (`7400`, `7404`, `7408`, `7432`, `7486`…), décodeurs/multiplexeurs (`7442`, `7446`–`7449`, `74138`, `74139`, `74151`, `74153`, `74157`…), arithmétique (`7483`, `74283`, `7485`, `74181`, `74280`, `74688`), bascules et registres (`7474`, `7476`, `74112`, `74174`, `74273`, `74374`, `74373`…), compteurs (`74160`–`74163`, `74168`, `74169`, `74190`–`74193`, `7490`, `7492`, `7493`, `74390`, `74393`), registres à décalage (`74164`, `74165`, `74194`, `74195`, `74198`, `74595`) et tampons (`74240`, `74244`, `74541`) |

Les paramètres (`LPM_WIDTH`, `LPM_DIRECTION`, `LPM_MODULUS`, `LPM_FILE`…) sont lus dans le `.bdf` ou dans les
fichiers VHDL/Verilog générés par le MegaWizard, et modifiables dans Digital (attribut *Quartus parameters*).
Si les noms de ports d'un symbole ne correspondent pas au modèle, le symbole est importé comme sous-bloc et
un avertissement est affiché.

### Mémoires

`LPM_ROM`, `LPM_RAM_DQ` et `ALTSYNCRAM` sont initialisées par le fichier indiqué dans `LPM_FILE` / `INIT_FILE`,
au format **MIF** (Memory Initialization File, avec plages `[0..7] : 0;` et toutes les bases) ou **Intel HEX**.
Le fichier est cherché à côté du schéma puis dans le projet.

### Chronogrammes (`.vwf`)

Les fichiers `.vwf` du projet (`VECTOR_WAVEFORM_FILE` du `.qsf`) ou du dossier du schéma sont convertis en
**cas de test** placés dans le circuit de plus haut niveau : les entrées du chronogramme sont rejouées, les
sorties enregistrées par une simulation Quartus précédente deviennent les valeurs attendues (les valeurs
inconnues `X` ne sont pas vérifiées). On lance les tests avec **Simulation → Run all tests** (F11).

## Export vers Quartus (`.bdf`)

**File → Export → Export to Quartus (bdf)** (ou `CLI quartusExport`) écrit le circuit courant en schéma Quartus :

- chaque composant Digital est remplacé par la primitive, la mégafonction ou la macrofonction équivalente
  (portes, bascules `DFF`/`TFF`/`JKFF`/`SRFF`, `LPM_FF`, `LPM_COUNTER`, `LPM_ADD_SUB`, `LPM_COMPARE`,
  `LPM_MULT`, `LPM_MUX`, `BUSMUX`, `LPM_DECODE`, `LPM_INV`, `LPM_CONSTANT`, `TRI`…) ; les composants Quartus
  du menu *Components → Quartus* sont réécrits tels quels ;
- les symboles sont dessinés **aux positions des broches Digital** : le schéma garde la même disposition et les
  fils sont reliés géométriquement ; un circuit importé depuis Quartus retrouve ses symboles d'origine ;
- les séparateurs (*splitters*) et tunnels sont remplacés par des **noms de bus** (`a[7..0]`, `a[3..0],b[3..0]`,
  `s[7],s[7],s[7..0]`…), les entrées inversées par des portes `NOT` ;
- les circuits intégrés (sous-circuits) sont exportés dans leurs propres `.bdf`, le contenu des ROM dans des `.mif` ;
- les éléments sans équivalent Quartus (transistors, interrupteurs, résistances de tirage, VHDL/Verilog
  intégré, `RAMDualPort`…) sont remplacés par un bloc vide et signalés par un avertissement, de même que les
  valeurs initiales des bascules (toujours 0 dans Quartus) ou les noms renommés (Quartus n'accepte que lettres,
  chiffres et `_` : `P<Q` devient `PltQ`).

Le résultat est vérifié par aller-retour (export puis réimport) sur les exemples de Digital : les cas de test
d'origine passent pour tous les circuits n'utilisant pas d'élément sans équivalent.

## Apparence « Quartus »

Le réglage **Edit → Settings → Quartus look** (activé par défaut) dessine aussi les entrées, sorties et
horloges des circuits Digital comme les broches `INPUT`/`OUTPUT` de Quartus et utilise les symboles de
portes ANSI/IEEE. Les circuits importés gardent toujours le dessin d'origine de Quartus.

## Limites connues

- AHDL (`.tdf`), les anciens schémas MAX+plus II (`.gdf`) et les symboles binaires `.sym` ne sont pas simulés.
- La simulation des blocs VHDL/Verilog nécessite GHDL ou Icarus Verilog (chemins dans *Settings*), et les
  bibliothèques Altera (`altera_mf`, `lpm`) ne sont pas fournies : seules les mégafonctions listées plus
  haut sont simulées sans elles.
- La simulation est fonctionnelle (sans temps de propagation) ; `ALTPLL` ne multiplie pas la fréquence.
- Les broches bidirectionnelles sont traitées comme des entrées.
- Les noms de ports des macrofonctions 74xx suivent la bibliothèque `maxplus2` de Quartus ; un symbole dont
  les ports diffèrent est importé comme sous-bloc.

## Pour les développeurs

Le code Kotlin se trouve dans `src/main/kotlin/de/neemann/digital/quartus` :

- `SExpr`, `BdfFile`, `BusName` : lecture des `.bdf`/`.bsf` et des noms de bus ;
- `QuartusProject` : `.qpf`, `.qsf`, `.qip` ; `HdlEntity` : interface des entités VHDL/modules Verilog ;
- `BdfConverter` : conversion d'un schéma (réseaux, bus, étiquettes) ; `QuartusImporter` : projet complet ;
- `MemoryFile` : `.mif`/`.hex` ; `VwfFile` : chronogrammes → cas de test ; `BdfExporter` : export `.bdf` ;
- `ShapeBuilder`, `SymbolFactory`, `QSymbol` et `draw/shapes/QuartusShape` (Java) : rendu à la Quartus ;
- `lib/` : `QuartusParts` (catalogue), `Primitives`, `Megafunctions`, `Macrofunctions` (modèles de simulation),
  `QuartusNode` (nœud de simulation générique), `Params`.

Les tests sont dans `src/test/java/de/neemann/digital/quartus` (`mvn test -Dtest='Quartus*,ParserTest'`).
