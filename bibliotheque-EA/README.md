# Bibliothèque EA

Déposez ici les fichiers de la bibliothèque Quartus **EA** : les blocs utilisés par les projets
(par exemple `Clock_Div`, `div2`, `div3`, `Div7`, `compteur2`, `affichage`, `detecteur_front_descendant`…).

## Fichiers à déposer

Pour chaque bloc, déposez tous les fichiers qui le décrivent :

| Extension | Contenu | Utilité |
|---|---|---|
| `.bdf` | schéma du bloc | indispensable pour simuler le bloc |
| `.vhd`, `.vhdl`, `.v` | bloc écrit en VHDL ou Verilog, fichiers du MegaWizard (`lpm_mux0.vhd`…) | indispensable pour simuler le bloc |
| `.bsf` | symbole du bloc | dessin du bloc tel qu'il apparaît dans Quartus |
| `.qip` | liste des fichiers d'une mégafonction | à joindre avec ses `.vhd`/`.v` |
| `.mif`, `.hex` | contenu des mémoires | si un bloc contient une ROM ou une RAM |
| `.tdf` | bloc AHDL | seule l'interface est lue (AHDL n'est pas simulé) |

Gardez les noms de fichiers d'origine : un bloc est retrouvé par son nom (`div2.bdf` pour le symbole `div2`).
Placez les fichiers directement dans ce dossier, sans sous-dossiers : l'import ne cherche pas plus profond.

## Comment déposer les fichiers

Sur GitHub : ouvrez ce dossier, puis **Add file → Upload files**, glissez les fichiers et validez
(*Commit changes*).

## Utilisation

À l'import d'un projet Quartus, les blocs absents du projet sont cherchés dans les dossiers voisins du projet.
Copiez donc ce dossier à côté de vos projets (par exemple `MesProjets/bibliotheque-EA` et
`MesProjets/Ohmmetre/Ohmmetre.qpf`), ou ajoutez son chemin aux bibliothèques du projet dans Quartus
(`USER_LIBRARIES` / `SEARCH_PATH` du `.qsf`).

## État de la bibliothèque (vérification)

| Bloc | Résultat |
|---|---|
| `div2`, `div3`, `div5`, `Div6` | divisent par 2, 3, 5 et 6 |
| `Clock_Div` (avec `CLK_DIV.VHD`) | 1 MHz, 100 kHz, 10 kHz… à partir de 50 MHz (VHDL simulé sans GHDL) |
| `CLK_DIV.VHD`, `DEBOUNCE.VHD`, `DEC_7SEG.VHD`, `ShiftRight16.vhd` | simulés par le simulateur VHDL intégré |
| `detect0vers1`, `detect1vers0` | impulsion sur front montant / descendant |
| `compteur3b`, `decompteurMod16`, `decompteurMod32`, `decode1parmi8`, `mux8par7` (AHDL MegaWizard) | simulés nativement |
| `Shift8`, `ShiftRight32` | registres à décalage corrects |
| `DriverAffich8D` | s'importe ; son symbole `ShiftRight16` n'a pas l'entrée `SerialIn` (ancienne version) |
| `Divideur7`, `XOR8`, `XOR4` | **reconstruits** (blocs manquants), voir [RECONSTRUCTIONS.md](RECONSTRUCTIONS.md) |
| `Div7` | sa bascule de sortie n'a pas d'horloge (fil `H` non relié à `E`), la sortie reste à 0 ; voir [RECONSTRUCTIONS.md](RECONSTRUCTIONS.md) |
| `GenerateurArinc` | s'importe sans avertissement avec `XOR8` et `XOR4` reconstruits |
| `Afficheur8D` | incomplet : `comptdecompt12b`, `affich3digit`, `InterfaceBouton` et `DriverAffich4D` manquent |
