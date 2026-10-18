# Compatibilité Quartus

Cette version de Digital sait importer les projets **Intel/Altera Quartus** (Quartus II, Quartus Prime)
pour les **visualiser**, les **modifier** et les **simuler**, avec un rendu graphique proche de l'éditeur de
schémas (*Block Editor*) de Quartus.

## Démarrage rapide

1. Construire l'application (Java 8 ou plus récent et Maven) :

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
```

## Fichiers pris en charge

| Fichier | Rôle | Prise en charge |
|---|---|---|
| `.qpf` | projet | lit `PROJECT_REVISION` pour trouver le `.qsf` |
| `.qsf` | réglages | `TOP_LEVEL_ENTITY`, fichiers du projet (`BDF_FILE`, `VHDL_FILE`, `VERILOG_FILE`, `QIP_FILE`…), `SEARCH_PATH`/`USER_LIBRARIES`, affectations de broches `set_location_assignment` (reprises comme numéros de broches) |
| `.qip` | fichiers d'IP (MegaWizard) | lus comme un `.qsf` (y compris `[file join $::quartus(qip_path) ...]`) |
| `.bdf` | schéma (*Block Design File*) | converti en schéma Digital : symboles, broches, fils, bus, étiquettes, jonctions, textes et dessins libres |
| `.bsf` | symbole (*Block Symbol File*) | utilisé pour l'interface d'un bloc quand la source est absente |
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

- Portes : `AND2`…`AND12`, `NAND`, `OR`, `NOR` (et variantes `B…` à entrées inversées), `XOR`, `XNOR`,
  `NOT`, `EXP`, `TRI`, tampons `WIRE`, `LCELL`, `GLOBAL`, `SOFT`, `CARRY`, `CASCADE`…, `VCC`, `GND`.
- Bascules : `DFF`, `DFFE`, `DFFEA`, `TFF`, `TFFE`, `JKFF`, `JKFFE`, `SRFF`, `SRFFE`, `LATCH`
  (avec `CLRN`/`PRN` asynchrones actifs à l'état bas).
- Mégafonctions : `LPM_COUNTER`, `LPM_SHIFTREG`, `LPM_FF`, `LPM_LATCH`, `LPM_CONSTANT`, `LPM_INV`,
  `LPM_COMPARE`, `LPM_ADD_SUB`, `LPM_DECODE`, `LPM_MULT`, `BUSMUX` — en symbole dans un `.bdf` ou via les
  fichiers VHDL/Verilog générés par le MegaWizard.
- Macrofonctions 74xx : `74160`–`74163`, `74190`–`74193`, `74194`, `74164`, `74138`, `74151`, `74153`,
  `74157`, `7446`–`7449`, `74283`, `7485`, `7474`, `7490`, `7493`.

Ces composants sont aussi disponibles dans le menu **Components → Quartus** pour dessiner de nouveaux
schémas. Si les noms de ports d'un symbole ne correspondent pas au modèle, le symbole est importé comme
sous-bloc et un avertissement est affiché.

## Apparence « Quartus »

Le réglage **Edit → Settings → Quartus look** (activé par défaut) dessine aussi les entrées, sorties et
horloges des circuits Digital comme les broches `INPUT`/`OUTPUT` de Quartus et utilise les symboles de
portes ANSI/IEEE. Les circuits importés gardent toujours le dessin d'origine de Quartus.

## Limites connues

- AHDL (`.tdf`), les anciens schémas MAX+plus II (`.gdf`) et les symboles binaires `.sym` ne sont pas simulés.
- La simulation des blocs VHDL/Verilog nécessite GHDL ou Icarus Verilog (chemins dans *Settings*), et les
  bibliothèques Altera (`altera_mf`, `lpm`) ne sont pas fournies : seules les mégafonctions listées plus
  haut sont simulées sans elles. Les mémoires (`altsyncram`, `lpm_rom`, ...) ne sont pas encore prises en charge.
- Les broches bidirectionnelles sont traitées comme des entrées.
- La conversion est à sens unique : les modifications sont enregistrées en `.dig` (pas de réécriture du `.bdf`).
- Les noms de ports des macrofonctions 74xx suivent la bibliothèque `maxplus2` de Quartus ; un symbole dont
  les ports diffèrent est importé comme sous-bloc.

## Pour les développeurs

Le code se trouve dans `src/main/java/de/neemann/digital/quartus` :

- `SExpr`, `BdfFile` : lecture des `.bdf`/`.bsf` ;
- `QuartusProject` : `.qpf`, `.qsf`, `.qip` ; `HdlEntity` : interface des entités VHDL/modules Verilog ;
- `BdfConverter` : conversion d'un schéma (réseaux, bus, étiquettes) ; `QuartusImporter` : projet complet ;
- `ShapeBuilder`, `SymbolFactory`, `draw/shapes/QuartusShape` : rendu à la Quartus ;
- `lib/QuartusParts` : modèles de simulation des primitives, LPM et 74xx.

Les tests sont dans `src/test/java/de/neemann/digital/quartus` (`mvn test -Dtest=Quartus*`).
