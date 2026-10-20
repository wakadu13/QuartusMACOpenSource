# Projet `C_3_jk` — compteur à bascules JK sur la carte DE0

Projet Quartus (Quartus II 9.0, carte DE0, `EP3C16F484C6`). Seuls les fichiers sources ont été gardés :
`C_3_jk.qpf`, `C_3_jk.qsf`, `C_3_jk.bdf`, `bitS.bdf`/`.bsf` et `C_3_jk.sof` (fichier de programmation de la carte).
Les fichiers produits par la compilation (bases `db`, rapports…) ont été retirés : Quartus les recrée.

## Fonctionnement

- `Clock_Div` (bibliothèque EA) divise l'horloge 50 MHz `H50M` (`PIN_G21`) ; sa sortie 1 Hz (`h`) cadence trois
  bascules `JKFF`.
- Les bascules forment un compteur synchrone à **5 états** : `Q[2..0]` = 0 → 2 → 4 → 5 → 1 → 0 …
  (`Q[0]` : J = `Q[2]`, K = NON `Q[2]` ; `Q[1]` : J = NON(`Q[2]` OU `Q[0]`), K = 1 ; `Q[2]` : J = `Q[1]`, K = `Q[0]`).
- `Q[3]` est relié à 0 ; `DEC_7SEG` (bibliothèque EA) affiche la valeur sur l'afficheur **HEX2** (segments `a` … `g`
  sur `PIN_D15` … `PIN_F14`, actifs à 0) : la carte affiche 0, 2, 4, 5, 1, 0… à raison d'un chiffre par seconde.
- L'entrée `z_BPclear` est affectée au bouton `BUTTON2` (`PIN_F1`) mais n'est reliée à rien dans le schéma :
  le bouton n'a pas d'effet.
- `bitS` fait partie du projet (listé dans le `.qsf`) mais n'est pas utilisé par `C_3_jk.bdf` :
  il calcule `S = E[0] OU E[2] OU E[3]` (vaut 0 seulement pour `E` = 0 et `E` = 2).

## Dans l'application

**Fichier → Ouvrir un projet ou un fichier Quartus** → `C_3_jk.qpf` : le projet s'ouvre directement sur la
**carte DE0 virtuelle** (afficheur HEX2, horloge 50 MHz, bouton BUTTON2). `Clock_Div` et `DEC_7SEG` sont trouvés
dans `bibliotheque-EA`. L'horloge de 50 MHz ne pouvant pas être simulée en temps réel, le chiffre change bien
plus lentement qu'une fois par seconde.

Vérifié en simulation : la suite affichée est 0, 2, 4, 5, 1, 0, 2…

## Sur la carte réelle

`C_3_jk.sof` se programme avec le *Programmer* de Quartus (voir le manuel, § 17). Pour recompiler dans Quartus,
ajoutez le dossier `bibliotheque-EA` aux bibliothèques du projet (*Assignments → Settings → Libraries*), car
`Clock_Div`, `CLK_DIV`, `div2` et `DEC_7SEG` y sont.
