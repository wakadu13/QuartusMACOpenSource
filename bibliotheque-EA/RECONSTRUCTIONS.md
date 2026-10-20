# Blocs reconstruits

Les fichiers ci-dessous **ne sont pas les originaux** de la bibliothèque EA : ces blocs étaient utilisés par
d'autres blocs mais absents des fichiers fournis. Ils ont été recréés le 20/10/2026 à partir des seuls indices
disponibles, puis vérifiés par simulation. Chaque fichier porte aussi la mention « RECONSTRUCTION » en en-tête.
Si les originaux sont retrouvés, il suffit de remplacer ces fichiers.

## Méthode

- Les **interfaces** (noms, sens et largeur des broches) sont lues sur les symboles des schémas qui utilisent
  ces blocs ; les **fonctions** sont déduites des noms, des connexions et de l'usage dans ces schémas.
- Les blocs sont construits **dans le style de la bibliothèque** : schémas Quartus (`.bdf`) faits de primitives,
  de mégafonctions LPM et de blocs existants, ou fichiers AHDL du MegaWizard.
- Tous les fichiers générés le sont par le script [`outils/reconstruction.py`](outils/reconstruction.py)
  (`python3 outils/reconstruction.py` depuis ce dossier) : il est la trace exacte de leur construction et
  permet de les régénérer. Seul `Divideur7.tdf` a été écrit à la main.
- Chaque bloc a été vérifié par simulation, et les tests automatiques de l'application le revérifient
  (`EaLibraryTest`).

| Fichier recréé | Utilisé par | Méthode |
|---|---|---|
| `Divideur7.tdf` | `Div7` | AHDL écrit à la main (`lpm_counter` modulo 7) |
| `XOR8.bdf`, `XOR4.bdf` | `GenerateurArinc` | script, arbre de portes `XOR` |
| `comptdecompt12b.bdf` (+ `.bsf`) | `Afficheur8D` | script, 3 `LPM_COUNTER` modulo 10 en cascade |
| `affich3digit.bdf` (+ `.bsf`) | `Afficheur8D` | script, 3 `DEC_7SEG` de la bibliothèque |
| `InterfaceBouton.bdf` (+ `.bsf`) | `Afficheur8D` | script, 3 `DFF`, `NOT`, `AND2` |
| `DriverAffich4D.bdf` (+ `.bsf`) | `Afficheur8D` | script, copie de `DriverAffich8D` avec `compteurMod4` |
| `compteurMod4.tdf` | `DriverAffich4D` | script, copie de `compteur3b` modulo 4 |
| `Div7_corrige.bdf` (+ `.bsf`) | — | copie corrigée de `Div7` |

Les symboles `.bsf` des blocs d'`Afficheur8D` sont copiés des instances dessinées dans `Afficheur8D.bdf` :
ils ont exactement la forme et les broches attendues.

## `Divideur7.tdf` (utilisé par `Div7`)

**Indices** : dans `Div7.bdf`, le symbole `Divideur7` a une entrée `clock` et deux sorties `q[2..0]` et `cout`.
C'est exactement l'interface d'un compteur `lpm_counter` créé avec le MegaWizard, comme les autres compteurs
de la bibliothèque (`compteur3b.tdf`, `decompteurMod16.tdf`), et le nom indique une division par 7.

**Construction** : fichier AHDL dans le même style que ceux du MegaWizard : un `lpm_counter` de 3 bits,
`LPM_DIRECTION = "UP"`, `LPM_MODULUS = 7`. Il compte 0, 1, …, 6, 0, … et `cout` vaut 1 quand `q = 6`
(une période d'horloge sur 7). Le fichier se compile aussi dans Quartus.

**Vérification** : `q` vaut successivement 1 2 3 4 5 6 0 1 … et `cout` est à 1 sur 6.

## `XOR8.bdf` et `XOR4.bdf` (utilisés par `GenerateurArinc`)

**Indices** : dans `GenerateurArinc.bdf`, le symbole `XOR8` (4 instances) a 8 entrées `IN1`…`IN8` et une sortie
`OUT`, le symbole `XOR4` 4 entrées `IN1`…`IN4` et une sortie `OUT` : la forme des portes de Quartus
(`AND8`, `OR4`…), mais pour un OU exclusif, qui n'existe pas en primitive à plus de 2 entrées. Dans un générateur
ARINC 429, ces portes calculent la parité du mot.

**Construction** : schémas Quartus (`.bdf`) formés d'un arbre de portes `XOR` à 2 entrées :
`OUT = IN1 ⊕ IN2 ⊕ … ⊕ IN8` (7 portes) et `OUT = IN1 ⊕ IN2 ⊕ IN3 ⊕ IN4` (3 portes). La sortie vaut 1 quand
un nombre impair d'entrées est à 1. Les fichiers ont été générés par un script, puis vérifiés par import.

**Confirmation** : dans `GenerateurArinc`, une entrée d'un `XOR8` est reliée à `VCC` ; avec un OU exclusif, le
bit 31 du mot est alors la parité **impaire** des 31 autres bits, ce qu'exige la norme ARINC 429. Le mot émis
en simulation est conforme.

**Vérification** : les 256 combinaisons de `XOR8` et les 16 de `XOR4` donnent la parité attendue ;
`GenerateurArinc` s'importe sans avertissement.

## `comptdecompt12b.bdf` (utilisé par `Afficheur8D`)

**Indices** : entrées `Up/Down` et `clk`, sortie `s[11..0]` reliée à `affich3digit` qui produit les unités,
dizaines et centaines (`u`, `d`, `c`). 12 bits = 3 chiffres de 4 bits ; l'ohmmètre de la même série affiche aussi
3 quartets (unités, dizaines, centaines) : c'est un compteur **décimal codé en binaire (BCD)**, de 000 à 999.
Le nom « comptdecompt » indique qu'il compte et décompte, selon `Up/Down`.

**Construction** : trois `LPM_COUNTER` de 4 bits, `LPM_MODULUS = 10`, entrée `updown` utilisée, en cascade
synchrone : les unités comptent à chaque front de `clk` ; les dizaines comptent quand la retenue `cout` des
unités vaut 1 (unités à 9 en comptant, à 0 en décomptant), reliée à leurs entrées `cnt_en` et `cin` ; de même
pour les centaines avec la retenue des dizaines. `s[3..0]` = unités, `s[7..4]` = dizaines, `s[11..8]` = centaines.

**Vérification** : de 000 à 999 puis 000 en comptant ; de 001 à 000 puis 999 en décomptant.

## `affich3digit.bdf` (utilisé par `Afficheur8D`)

**Indices** : entrée `E[11..0]` (la sortie du compteur), sorties `c[1..7]`, `d[1..7]`, `u[1..7]` : 7 segments
pour les centaines, dizaines et unités. Ces sorties sont aussi envoyées à `DriverAffich4D`, dont les afficheurs
inutilisés sont mis à `VCC` (éteints) : les segments sont donc **actifs à 0**, comme sur la DE0.

**Construction** : trois décodeurs `DEC_7SEG` de la bibliothèque (VHDL) : `E[11..8]` → `c`, `E[7..4]` → `d`,
`E[3..0]` → `u`. Choix fait : l'indice 1 est le segment a, l'indice 7 le segment g (`c[1]` = a … `c[7]` = g).
Si l'ordre des segments ne correspond pas à votre câblage, il suffit de changer les étiquettes `c[1]`…`c[7]`
dans le script.

**Vérification** : `E = 857` (BCD) affiche 8, 5 et 7.

## `InterfaceBouton.bdf` (utilisé par `Afficheur8D`)

**Indices** : entrées `E1`, `E2` et une horloge `H10k` (reliée au 1 kHz dans `Afficheur8D`), sorties `Sens`
(vers `Up/Down` du compteur) et `Pulse` (vers son horloge). Deux entrées qui donnent un **sens** et une
**impulsion** de comptage : c'est l'interface d'un **bouton rotatif (codeur incrémental)**, dont les deux voies
A et B sont décalées d'un quart de période ; le sens de rotation se lit sur l'état de B au front de A.

**Construction** : trois bascules `DFF` cadencées par `H10k` échantillonnent `E1` (deux fois, pour détecter son
front) et `E2` ; l'échantillonnage synchronise les entrées et filtre les rebonds plus courts qu'une période.
`Pulse = E1 échantillonné ET NON(E1 échantillonné à la période précédente)` : une impulsion d'une période
d'horloge à chaque front montant de `E1`. `Sens = NON(E2 échantillonné)` : 1 (compter) quand `E1` est en avance
sur `E2`, 0 (décompter) dans l'autre sens.

**Hypothèse à confirmer** : si `E1`/`E2` étaient en réalité deux boutons poussoirs (« + » et « − »), ce bloc
ne conviendrait pas ; il faudrait alors `Pulse` = front de `E1` OU de `E2` et `Sens` = `E1`.

**Vérification** : 2 tours dans un sens donnent 2 impulsions avec `Sens = 1`, dans l'autre sens 2 impulsions
avec `Sens = 0`.

## `DriverAffich4D.bdf` et `compteurMod4.tdf` (utilisés par `Afficheur8D`)

**Indices** : le symbole `DriverAffich4D` a exactement les mêmes broches que `DriverAffich8D` (`d7`…`d0`,
`dot`, `f50Meg`, `pulse`, `SerialData`, `SerialClock`). Dans `Afficheur8D`, seuls `d0` à `d2` (unités, dizaines,
centaines) sont utilisés ; `d3` à `d7` et les points sont reliés à `VCC`. C'est la version 4 afficheurs du
pilote série (module à 4 afficheurs), le 4e afficheur restant éteint.

**Construction** : copie de `DriverAffich8D.bdf` où le compteur d'afficheurs `compteur3b` (0 à 7) est remplacé
par `compteurMod4` (copie de `compteur3b.tdf` avec `LPM_MODULUS = 4`, 0 à 3). Tout le reste est identique :
trames de 16 bits (point, 7 segments, sélection 1 parmi 8 dont seuls les bits 8 à 11 servent), une trame par
milliseconde, chacun des 4 afficheurs rafraîchi toutes les 4 ms. La copie garde l'ancien symbole
`ShiftRight16` (sans `SerialIn`) de `DriverAffich8D`, d'où un avertissement sans conséquence.

**Vérification** : les trames envoient successivement les afficheurs 0, 1, 2, 3, 0…

## Projet `Afficheur8D` complet

Avec ces quatre blocs, `Afficheur8D` s'importe et fonctionne : en simulation, 3 crans du bouton dans un sens et
1 dans l'autre affichent 002.

## `Div7_corrige.bdf` et `Div7_corrige.bsf` : copie corrigée de `Div7`

Le fichier original `Div7.bdf` est conservé **sans modification**. Il contient un défaut, signalé sur le schéma
imprimé (« Attention bugg dans le Div7 ») : l'horloge de la bascule `DFF` de sortie est reliée à un fil nommé
`H`, alors que l'entrée du bloc s'appelle `E`. Aucun autre fil ne s'appelle `H` : la bascule ne reçoit jamais
d'horloge et la sortie `S` reste à 0, dans Quartus comme ici.

**Construction de la copie** : `Div7.bdf` a été copié en `Div7_corrige.bdf` avec une seule modification :
le fil `H` est supprimé et l'entrée `CLK` de la bascule est reliée par un fil à l'entrée `E` (point de jonction
sur le fil d'entrée). Un commentaire en tête du fichier décrit la correction. Le symbole `Div7.bsf` a été copié
en `Div7_corrige.bsf` avec le nouveau nom.

**Vérification** : `Div7_corrige` s'importe sans avertissement et `S` vaut 1 pendant une période de `E` sur 7.

Dans un projet, il suffit d'utiliser le symbole `Div7_corrige` à la place de `Div7`.
