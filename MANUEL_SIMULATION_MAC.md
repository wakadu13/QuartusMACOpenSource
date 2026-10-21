# Suivre le manuel « Simulation sous Quartus » sur Mac

> Ce guide existe aussi dans l'application, avec une animation par étape : **Aide → Guide : le livret Quartus pas à
> pas**. Le circuit `ouex` du livret est dans [`projets/ouex`](projets/ouex/ouex.dig).

Ce guide reprend, étape par étape, le livret étudiant *Apprentissage par problèmes — Électronique numérique*
(§ 14 à 17 : simulation sous Quartus, assignation des entrées-sorties, programmation de la DE0) et indique comment
faire la même chose dans QuartusMACOpenSource, sans Quartus. L'exemple est celui du livret : un circuit `ouex`
avec deux entrées `E1`, `E2` et une sortie `S` (un OU exclusif).

## § 14 — Analysis & Synthesis

Dans Quartus, cette commande vérifie le câblage et prépare la simulation.

Dans l'application, il n'y a rien à faire : les erreurs de câblage (fil non relié, deux sorties sur un même fil,
largeurs de bus différentes…) sont signalées au lancement de la simulation (**Simulation → Lancer la simulation**,
ou ▶). Pour un schéma Quartus, ouvrez-le avec **Fichier → Ouvrir un projet ou un fichier Quartus** : les problèmes
trouvés à l'import (bloc introuvable, port inconnu…) sont affichés à l'ouverture.

## § 15 — Simulation

### Simuler une sous-partie (« Set as Top Level Entity »)

Inutile de changer le schéma de plus haut niveau : ouvrez directement le `.bdf` du bloc à simuler (**Fichier →
Ouvrir un projet ou un fichier Quartus**), ou, dans un projet déjà importé, le fichier `.dig` du bloc dans le dossier
`digital/`. Chaque bloc importé est un circuit qu'on peut simuler seul.

### Simulator Tool, mode Functional, Generate Functional Simulation Netlist

Inutile aussi : le circuit est préparé automatiquement à chaque lancement. La simulation est **fonctionnelle**
(sans temps de propagation), comme le mode *Functional* de Quartus ; l'éditeur de chronogrammes propose aussi un
mode **temporel** qui donne un délai à chaque porte.

### § 15.1 — Créer les signaux d'entrée (fichier `.vwf`)

Quatre façons de faire, au choix :

0. **L'éditeur de chronogrammes**, le plus proche de Quartus : **Quartus → Éditeur de chronogrammes**. Les entrées
   `E1`, `E2` et la sortie `S` sont listées ; on dessine les entrées à la souris (clic, glisser, `Maj`+glisser pour un
   intervalle, boutons *Horloge…*, *Compteur…*, *Valeur…*), on règle **Durée (End Time)** et **Grille (Grid Size)**
   — par exemple `10 ms` et `0.5 ms`, avec `E1` horloge d'une demi-période d'un pas (1 ms) et `E2` de deux pas
   (2 ms) — et `S` est recalculée à chaque modification. **Enregistrer…** écrit un fichier `.vwf` que Quartus
   ouvre aussi.

1. **Commander les entrées à la main** : lancez la simulation (▶) et cliquez sur les entrées `E1`, `E2` ; la
   sortie `S` s'affiche aussitôt. C'est le plus rapide pour vérifier une table de vérité.

2. **Appliquer des horloges, comme dans le livret** (E1 de période 1 ms, E2 de période 2 ms) :
   - remplacez l'entrée `E1` par un élément **Horloge** (même nom `E1`) ; dans ses propriétés, cochez
     *Partir l'horloge temps réel* et mettez *Fréquence/Hz* = **1000** (période 1 ms) ;
   - faites de même pour `E2` avec **500** Hz (période 2 ms) ;
   - les horloges, entrées et sorties apparaissent dans le graphique par défaut (option **Afficher dans le
     graphique de mesures** des entrées/sorties) ;
   - lancez la simulation, puis **Simulation → Afficher le graphique des mesures** : on obtient les
     chronogrammes du livret (équivalent de la fenêtre de résultats du *Simulator Tool*).

3. **Écrire un test**, l'équivalent d'un `.vwf` avec des résultats attendus : ajoutez un élément **Test**
   (menu *Composants → Divers*) et écrivez par exemple :

   ```
   E1 E2 S
   0  0  0
   1  0  1
   0  1  1
   1  1  0
   ```

   **Simulation → Exécuter les tests** (F11) indique les lignes fausses et affiche le chronogramme.
   Un fichier `.vwf` venant de Quartus est d'ailleurs converti automatiquement en test à l'import.

« End Time » et « Grid Size » se règlent dans l'éditeur de chronogrammes ; avec les autres méthodes, la simulation
dure tant qu'on ne l'arrête pas, et le graphique enregistre chaque changement.

### § 15.2 — Lancer la simulation et voir les résultats

▶ lance la simulation ; les valeurs s'affichent sur le schéma (fils en couleur) et dans le graphique des mesures.
On peut y vérifier que `S` est bien le OU exclusif de `E1` et `E2`.

## § 16 — Assignation des entrées-sorties (Pin Planner)

Le livret relie `E1` à l'interrupteur SW0 (`J6`), `E2` à SW1 (`H5`) et `S` à la LED LEDG0 (`J1`).

- Pour un projet Quartus qui a déjà ses affectations (dans le `.qsf`), rien à faire : elles sont lues à l'import
  et affichées à côté des broches (cadres `PIN_xx`).
- Pour un circuit dessiné dans l'application, le plus simple est **Quartus → Pin Planner** : la carte DE0 est
  dessinée, on choisit `E1` dans la liste puis on clique sur **SW0**, de même `E2` sur **SW1** et `S` sur **LEDG0**.
- On peut aussi, dans les propriétés de chaque entrée/sortie, remplir
  **Numéro de broche** (`J6`, `H5`, `J1`, avec ou sans `PIN_`). Pour un bus, donnez les broches séparées par des
  virgules, bit 0 en premier. Le fichier `bibliotheque-EA/PinPlaner.xls` donne toutes les broches de la carte.

Ensuite **Fichier → Afficher sur la carte DE0** (après avoir enregistré le circuit) ouvre une **carte DE0
virtuelle** : les interrupteurs, boutons, LED, afficheurs et l'horloge 50 MHz y sont reliés selon les numéros de
broche. On peut cliquer sur SW0/SW1 et voir LEDG0 s'allumer, comme sur la vraie carte.

## § 17 — Programmation du FPGA de la DE0

Cette étape demande Quartus (compilation et *Programmer*) et la carte reliée en USB : elle se fait sur un PC de
l'école. Pour y arriver avec ce qui a été fait sur le Mac :

1. **Fichier → Exporter → Exporter vers Quartus (bdf)** : le circuit est écrit en schéma Quartus (`.bdf`) et, si
   le dossier ne contient pas déjà de projet, un projet Quartus est créé à côté (`.qpf` et `.qsf` pour la DE0,
   `EP3C16F484C6`) avec les **numéros de broche** des entrées-sorties.
2. Sur le PC de l'école : ouvrir le `.qpf` dans Quartus, compiler (▶), puis programmer la carte avec le fichier
   `.sof` produit (*Programmer*, *USB-Blaster*, case *Program/Configure* cochée, carte en mode *Run*).

Un projet déjà compilé contient son `.sof` (par exemple `projets/C_3_jk/C_3_jk.sof`), qu'on peut programmer
directement.

## TP auto-corrigés

Un TP donné par l'enseignant (fichier `.dig` créé avec **Quartus → Créer un TP à partir de ce circuit**) contient des
tests cachés. On dessine le circuit puis **Quartus → Vérifier mon TP** affiche la note et, si l'enseignant l'a permis,
la première ligne fausse, par exemple `E1=1 E2=1 → S : attendu 0, obtenu 1`.
