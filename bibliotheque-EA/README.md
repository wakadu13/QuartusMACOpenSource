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
Les sous-dossiers sont autorisés.

## Comment déposer les fichiers

Sur GitHub : ouvrez ce dossier, puis **Add file → Upload files**, glissez les fichiers et validez
(*Commit changes*).

## Utilisation

À l'import d'un projet Quartus, les blocs absents du projet sont cherchés dans les dossiers voisins du projet.
Copiez donc ce dossier à côté de vos projets (par exemple `MesProjets/bibliotheque-EA` et
`MesProjets/Ohmmetre/Ohmmetre.qpf`), ou ajoutez son chemin aux bibliothèques du projet dans Quartus
(`USER_LIBRARIES` / `SEARCH_PATH` du `.qsf`).
