# Blocs reconstruits

Les fichiers ci-dessous **ne sont pas les originaux** de la bibliothèque EA : ces blocs étaient utilisés par
d'autres blocs mais absents des fichiers fournis. Ils ont été recréés le 20/10/2026 à partir des seuls indices
disponibles, puis vérifiés par simulation. Chaque fichier porte aussi la mention « RECONSTRUCTION » en en-tête.
Si les originaux sont retrouvés, il suffit de remplacer ces fichiers.

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

**Vérification** : les 256 combinaisons de `XOR8` et les 16 de `XOR4` donnent la parité attendue ;
`GenerateurArinc` s'importe sans avertissement.

## Défaut trouvé dans `Div7.bdf` (non corrigé)

Le fichier original `Div7.bdf` n'a pas été modifié. Il contient un défaut, signalé sur le schéma imprimé
(« Attention bugg dans le Div7 ») : l'horloge de la bascule `DFF` de sortie est reliée à un fil nommé `H`,
alors que l'entrée du bloc s'appelle `E`. Aucun autre fil ne s'appelle `H` : la bascule ne reçoit jamais
d'horloge et la sortie `S` reste à 0, dans Quartus comme ici.

**Correction possible** : renommer le fil `H` en `E` (ou nommer `H` le fil de l'entrée `E`). Avec cette
correction, `Div7` divise bien par 7 (impulsion d'une période d'horloge toutes les 7 périodes).
