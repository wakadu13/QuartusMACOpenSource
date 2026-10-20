# Bibliothèque EA

Ce dossier contient la bibliothèque Quartus **EA** : des blocs réutilisables pour la carte DE0
(diviseurs d'horloge, compteurs, détecteurs de fronts, registres à décalage, pilotes d'afficheurs…).
Chaque bloc a été importé et simulé ; son fonctionnement est décrit plus bas.

- [Utilisation](#utilisation)
- [État de la bibliothèque](#état-de-la-bibliothèque)
- [Fonctionnement de chaque élément](#fonctionnement-de-chaque-élément)
- [Blocs reconstruits et copie corrigée](RECONSTRUCTIONS.md)
- [Ajouter des fichiers](#ajouter-des-fichiers)

## Utilisation

À l'import d'un projet Quartus, les blocs absents du projet sont cherchés dans les dossiers voisins du projet.
Copiez donc ce dossier à côté de vos projets (par exemple `MesProjets/bibliotheque-EA` et
`MesProjets/Ohmmetre/Ohmmetre.qpf`), ou ajoutez son chemin aux bibliothèques du projet dans Quartus
(`USER_LIBRARIES` / `SEARCH_PATH` du `.qsf`).

Les fichiers VHDL sont simulés par le simulateur VHDL intégré à l'application (GHDL n'est pas nécessaire),
les blocs AHDL créés par le MegaWizard (`lpm_counter`, `lpm_decode`, `lpm_mux`) sont simulés directement.

## État de la bibliothèque

| Bloc | Résultat de la vérification |
|---|---|
| `div2`, `div3`, `div5`, `Div6` | divisent par 2, 3, 5 et 6 |
| `Div7` (original) | **défaut** : la sortie reste à 0 (horloge de la bascule non reliée), voir [RECONSTRUCTIONS.md](RECONSTRUCTIONS.md) |
| `Div7_corrige` | copie corrigée de `Div7` : divise par 7 |
| `Clock_Div`, `CLK_DIV.VHD` | 1 MHz, 100 kHz, 10 kHz… exacts à partir de 50 MHz |
| `DEBOUNCE.VHD`, `DEC_7SEG.VHD`, `ShiftRight16.vhd` | corrects |
| `detect0vers1`, `detect1vers0` | impulsion sur front montant / descendant |
| `compteur3b`, `decompteurMod16`, `decompteurMod32`, `decode1parmi8`, `mux8par7` | corrects |
| `Shift8`, `ShiftRight32` | corrects |
| `DriverAffich8D` | trames série correctes ; son symbole `ShiftRight16` est une ancienne version sans `SerialIn` |
| `GenerateurArinc` | mot ARINC 429 correct (parité comprise) |
| `Divideur7`, `XOR8`, `XOR4` | **reconstruits** (absents des fichiers fournis), voir [RECONSTRUCTIONS.md](RECONSTRUCTIONS.md) |
| `Afficheur8D` | incomplet : `comptdecompt12b`, `affich3digit`, `InterfaceBouton` et `DriverAffich4D` manquent |

## Fonctionnement de chaque élément

Notations : « ↑ » est un front montant, « période » une période de l'horloge du bloc. Les résultats donnés entre
parenthèses ont été mesurés en simulation.

### Horloges et diviseurs de fréquence

#### `div2` — diviseur par 2
- **Entrée** `E` : horloge. **Sortie** `S`.
- Une bascule `JKFF` avec `J = K = 1` change d'état à chaque ↑ de `E` : `S` est un signal carré de fréquence
  `E / 2`, rapport cyclique 50 %.

#### `div3` — diviseur par 3
- **Entrée** `E`, **sortie** `S`.
- Deux bascules `JKFF` forment un compteur à 3 états (00 → 01 → 10 → 00) : la première a `J = Q` de la seconde,
  la seconde a `J = NON S`, toutes deux `K = 1`.
- `S` vaut 1 pendant 1 période sur 3 (fréquence `E / 3`, impulsion et non signal carré).

#### `div5` — diviseur par 5
- **Entrée** `E`, **sortie** `S`.
- Trois bascules `JKFF` et une porte `AND3` forment un compteur à 5 états ; `S` vaut 1 pendant 1 période sur 5
  (fréquence `E / 5`).

#### `Div6` — diviseur par 6
- **Entrée** `E`, **sortie** `S`.
- `div3` suivi de `div2` : `S` est un signal carré de fréquence `E / 6` (3 périodes à 1, 3 à 0).

#### `Div7` — diviseur par 7 (original, défectueux)
- **Entrée** `E`, **sortie** `S`.
- Prévu ainsi : le compteur `Divideur7` (modulo 7) compte les fronts de `E` ; sa sortie de retenue `cout`
  (1 période sur 7) est recopiée par une bascule `DFF` pour donner une sortie propre.
- **Défaut** : l'horloge de la bascule est reliée à un fil nommé `H` qui n'est relié à rien ; la bascule n'est
  jamais cadencée et `S` reste à 0. C'est le « bugg » signalé sur le schéma imprimé.

#### `Div7_corrige` — diviseur par 7 (copie corrigée)
- Identique à `Div7`, mais l'horloge de la bascule est reliée par un fil à l'entrée `E`.
- `S` vaut 1 pendant 1 période sur 7 (fréquence `E / 7`).

#### `Divideur7` — compteur modulo 7 (reconstruit)
- **Entrée** `clock`. **Sorties** `q[2..0]` (valeur du compteur) et `cout` (retenue).
- `lpm_counter` de 3 bits : `q` compte 0, 1, …, 6, 0, … à chaque ↑ de `clock` ; `cout = 1` quand `q = 6`.

#### `CLK_DIV.VHD` (entité `clk_div`) — diviseur décimal
- Bloc VHDL classique de la bibliothèque « UP core » d'Altera.
- **Entrée** `clock_25Mhz` (25 MHz). **Sorties** `clock_1MHz`, `clock_100KHz`, `clock_10KHz`, `clock_1KHz`,
  `clock_100Hz`, `clock_10Hz`, `clock_1Hz`.
- Un compteur de 0 à 24 donne 1 MHz (25 périodes, 13 à 1) ; puis chaque étage divise par 10 le précédent
  (compteur 0..4 et bascule qui change d'état), avec un rapport cyclique de 50 %.
- Toutes les sorties sont recopiées dans des registres sur le front de l'horloge 25 MHz : elles changent donc
  au même instant.

#### `Clock_Div` — horloges de la carte DE0
- **Entrée** `50MHz` (horloge de la carte, `PIN_G21`).
  **Sorties** `1MHz`, `100KHz`, `10KHz`, `1KHz`, `100Hz`, `10Hz`, `1Hz`, `5Hz`.
- `div2` ramène 50 MHz à 25 MHz, `clk_div` fournit les fréquences décimales, un second `div2` donne 5 Hz
  à partir de 10 Hz (mesuré : 1 MHz = 50 périodes de 50 MHz, 100 kHz = 500, 10 kHz = 5000).

### Compteurs, décodeur et multiplexeur (blocs AHDL créés par le MegaWizard)

Ces blocs sont des enveloppes de mégafonctions : le fichier `.tdf` contient les paramètres, le `.inc` la
déclaration du bloc pour d'autres fichiers AHDL, le `.qip` la liste des fichiers et le `.bsf` le symbole.

#### `compteur3b` — compteur 3 bits
- **Entrées** `clock`, `aclr`. **Sortie** `q[2..0]`.
- `lpm_counter` croissant : 0 → 7 puis retour à 0 à chaque ↑ de `clock` ; `aclr = 1` remet à 0 immédiatement.

#### `decompteurMod16` — décompteur 5 bits
- **Entrées** `clock`, `cnt_en`. **Sorties** `q[4..0]`, `cout`.
- `lpm_counter` décroissant sur 5 bits : 31, 30, …, 0, 31… tant que `cnt_en = 1` ; `cout = 1` quand `q = 0`.
- Le nom vient de son usage : ses 32 états font 16 périodes de `q[0]`, donc 16 coups d'horloge série
  (voir `DriverAffich8D`).

#### `decompteurMod32` — décompteur 6 bits
- Comme `decompteurMod16` sur 6 bits : 63 → 0, soit 32 périodes de `q[0]` (32 bits d'un mot ARINC,
  voir `GenerateurArinc`).

#### `decode1parmi8` — décodeur 1 parmi 8
- **Entrée** `data[2..0]`. **Sorties** `eq0` … `eq7`.
- `lpm_decode` : seule la sortie `eqN` correspondant à la valeur `N` de `data` est à 1.

#### `mux8par7` — multiplexeur de 8 mots de 8 bits
- **Entrées** `data0x[7..0]` … `data7x[7..0]`, `sel[2..0]`. **Sortie** `result[7..0]`.
- `lpm_mux` : `result` recopie le mot `dataNx` choisi par `sel = N` (un afficheur : 7 segments et le point).

### Détecteurs de fronts

#### `detect0vers1` — détecteur de front montant
- **Entrées** `entree` (signal à surveiller), `f100k` (horloge d'échantillonnage). **Sortie** `imp`.
- Une bascule `DFF` mémorise `entree` à chaque ↑ de `f100k` ; `imp = entree ET NON(entree mémorisée)`.
- `imp` passe à 1 **dès** que `entree` monte et retombe au front suivant de `f100k` (au plus une période).
  La sortie n'est donc pas synchronisée sur l'horloge.
- Si `entree` change au même instant qu'un front de `f100k` (par exemple deux sorties de `Clock_Div`),
  l'impulsion a une durée nulle en simulation fonctionnelle : c'est un aléa du montage.

#### `detect1vers0` — détecteur de front descendant
- **Entrées** `entree`, `f100k`. **Sortie** `imp`.
- Deux bascules `DFF` en série échantillonnent `entree` sur `f100k` ; `imp = (ancienne valeur) ET NON(nouvelle)`.
- `imp` vaut 1 pendant exactement une période de `f100k` après chaque front descendant de `entree`.
  Le montage est entièrement synchrone (sortie propre, légèrement retardée).

#### `DEBOUNCE.VHD` (entité `debounce`) — anti-rebond de bouton poussoir
- Bloc VHDL de la bibliothèque « UP core » d'Altera.
- **Entrées** `pb` (bouton, **actif à 0** comme sur la DE0), `clock_100Hz`. **Sortie** `pb_debounced`.
- À chaque ↑ de l'horloge 100 Hz, l'état inversé du bouton entre dans un registre de 4 bits.
  `pb_debounced` passe à 1 au premier échantillon « appuyé » et ne revient à 0 qu'après 4 échantillons
  « relâché » de suite (40 ms) : les rebonds sont ignorés. La sortie est **active à 1**.

### Registres à décalage

#### `Shift8` — registre à décalage 8 bits
- **Entrées** `E` (donnée série), `H` (horloge). **Sorties** `Q[7..0]`, `S`.
- Huit bascules `DFF` en chaîne : à chaque ↑ de `H`, `E` entre dans `Q[7]` et chaque bit descend d'un rang
  (`Q[7]` → `Q[6]` … → `Q[0]`). `S = Q[0]` est la sortie série (la donnée ressort 8 fronts plus tard).

#### `ShiftRight16.vhd` — registre 16 bits à chargement parallèle
- VHDL écrit par Quartus à partir d'un schéma.
- **Entrées** `Load`, `h` (horloge), `SerialIn`, `data[15..0]`. **Sortie** `SerialOut`.
- Tant que `Load = 1`, les 16 bascules prennent la valeur de `data` (chargement **asynchrone**, par les
  entrées `PRN`/`CLRN` des bascules).
- Quand `Load = 0`, chaque ↑ de `h` décale vers la droite : `SerialIn` entre dans le bit 15, le bit 0 sort sur
  `SerialOut`. Les bits sortent donc **bit 0 en premier**.

#### `ShiftRight32` — registre 32 bits à chargement parallèle
- **Entrées** `load`, `h`, `SerialIn`, `data[31..0]`. **Sortie** `SerialOut`.
- Deux `ShiftRight16` en série (`data[31..16]` puis `data[15..0]`) : même fonctionnement sur 32 bits,
  bit 0 en premier.

### Portes reconstruites

#### `XOR8`, `XOR4` — OU exclusif à 8 et 4 entrées (reconstruits)
- **Entrées** `IN1` … `IN8` (ou `IN4`). **Sortie** `OUT`.
- `OUT` vaut 1 si un nombre **impair** d'entrées est à 1 (calcul de parité), réalisé par un arbre de portes
  `XOR` à 2 entrées.

### Pilotes et générateurs

#### `DriverAffich8D` — pilote série de 8 afficheurs 7 segments
- **Entrées** `f50Meg` (50 MHz), `d0[7..1]` … `d7[7..1]` (segments des afficheurs 0 à 7), `dot[7..0]`
  (points décimaux). **Sorties** `SerialClock`, `SerialData`, `pulse`.
- Il envoie l'état des afficheurs, un afficheur à la fois, à un module d'affichage série (registres à décalage
  externes) :
  1. `Clock_Div` fournit 1 MHz, 100 kHz et 1 kHz.
  2. À chaque ↑ du 1 kHz, `detect0vers1` produit `pulse`, qui charge le registre `ShiftRight16` et fait
     avancer `compteur3b` (numéro de l'afficheur, de 0 à 7).
  3. Le mot chargé (16 bits) contient : bit 0 = point `dot[N]`, bits 1 à 7 = segments `dN[1..7]` (choisis par
     `mux8par7`), bits 8 à 15 = sélection de l'afficheur en « 1 parmi 8 » (`decode1parmi8`).
  4. Au front descendant du 1 kHz, `detect1vers0` lance `decompteurMod16` (horloge 1 MHz) ; son bit `q[0]`
     donne 16 périodes de `SerialClock` à 500 kHz, et le registre envoie les 16 bits sur `SerialData`, bit 0
     en premier.
- Un afficheur est donc rafraîchi toutes les 1 ms, chacun des 8 toutes les 8 ms (125 Hz).
- Le symbole `ShiftRight16` de ce schéma est une ancienne version sans entrée `SerialIn` (laissée à 0).

#### `GenerateurArinc` — émetteur ARINC 429 basse vitesse
- **Entrées** `H50Meg` (50 MHz), `Label[7..0]`, `SDI[1..0]`, `D[18..0]` (données), `SSM[1..0]`.
  **Sorties** `S1`, `S0` (les deux fils de la ligne), `Synchro`.
- Le mot de 32 bits est formé de : bits 0 à 7 = `Label`, 8-9 = `SDI`, 10 à 28 = `D`, 29-30 = `SSM`,
  bit 31 = **parité impaire** (4 `XOR8` + 1 `XOR4`, une entrée reliée à `VCC`).
- Cadencement : 1 kHz ÷ 5 (`div5`) = 200 Hz ; à chaque ↑ de ce signal, `Synchro` charge le mot dans
  `ShiftRight32`. Au front descendant, `decompteurMod32` (horloge 25 kHz = 100 kHz ÷ 4) produit 32 périodes
  de `q[0]` : un bit toutes les 80 µs, soit **12,5 kbit/s** (vitesse basse ARINC 429).
- Codage bipolaire avec retour à zéro : pendant la première moitié de chaque bit, `S1 = 1` pour un « 1 » ou
  `S0 = 1` pour un « 0 » ; les deux lignes sont à 0 pendant la seconde moitié et entre deux mots.
- Mesuré : un mot toutes les 5 ms, bits envoyés bit 0 en premier, parité correcte.
  La norme ARINC 429 envoie le label bit de poids fort en premier : il faut donc câbler `Label` à l'envers
  pour la respecter.

### Projet complet

#### `Afficheur8D` — compteur à 3 chiffres (projet incomplet)
- Fichiers de projet : `Afficheur8D.qpf`, `Afficheur8D.qsf` (carte DE0, EP3C16F484C6), `Afficheur8D.qws`.
- **Entrées** `H50Meg`, `E1`, `E2`, `BP`, `BP1`. **Sorties** `u[1..7]`, `d[1..7]`, `c[1..7]` (unités, dizaines,
  centaines en 7 segments), `LED`, `SerialClock`, `SerialData`.
- `InterfaceBouton` transforme `E1` et `E2` (probablement un codeur rotatif ou deux boutons) en un sens de
  comptage et une impulsion ; `comptdecompt12b` compte ou décompte sur 12 bits ; `affich3digit` convertit la
  valeur en 3 chiffres 7 segments, envoyés aussi à un module série par `DriverAffich4D`. `LED` recopie `BP`.
- **Ne fonctionne pas tel quel** : `comptdecompt12b`, `affich3digit`, `InterfaceBouton` et `DriverAffich4D`
  ne sont pas dans la bibliothèque (importés comme blocs vides).

### Autres fichiers

| Fichier | Contenu |
|---|---|
| `DEC_7SEG.VHD` (entité `dec_7seg`) | décodeur hexadécimal → 7 segments de la bibliothèque « UP core » : entrée `hex_digit[3..0]`, sorties `segment_a` … `segment_g` **actives à 0** (0 = segment allumé, comme sur la DE0), chiffres 0 à 9 et lettres A à F |
| `*.inc` | déclarations AHDL des blocs du MegaWizard (pour les utiliser dans d'autres fichiers AHDL) |
| `*.qip` | listes des fichiers des blocs du MegaWizard |
| `*.bsf` | symboles des blocs (dessin dans l'éditeur de schémas) |
| `CLK_DIV.SYM`, `DEBOUNCE.SYM`, `DEC_7SEG.SYM` | anciens symboles MAX+plus II des blocs VHDL (non utilisés) |
| `PinPlaner.xls` | affectation des broches de la DE0 : afficheurs `HEX0` à `HEX3` (Unités, Dizaines, Centaines, Milliers) et leurs points, LED `Led0` à `Led9`, interrupteurs `SW0` à `SW9`, boutons `BP0` à `BP2`, horloge 50 MHz (`G21`) |
| `RECONSTRUCTIONS.md` | comment les blocs manquants ont été recréés et `Div7` corrigé |

## Ajouter des fichiers

Pour chaque bloc, déposez tous les fichiers qui le décrivent (`.bdf`, `.vhd`/`.v`, `.tdf`, `.bsf`, `.qip`,
`.inc`, `.mif`/`.hex`), avec leurs noms d'origine (un bloc est retrouvé par son nom), directement dans ce
dossier, sans sous-dossiers. Sur GitHub : ouvrez ce dossier, puis **Add file → Upload files**, glissez les
fichiers et validez avec **Commit changes**.
