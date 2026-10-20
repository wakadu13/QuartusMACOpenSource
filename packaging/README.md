# Paquets d'installation

Les versions pour **macOS (Apple Silicon)**, **Windows** et **Linux** sont construites par GitHub Actions
(`.github/workflows/release.yml`) avec `jpackage` : chaque paquet contient l'application et un Java réduit aux
modules utiles (≈ 75 Mo), il n'y a donc rien à installer à côté.

Publier une nouvelle version :

```
git tag v1.0.1
git push origin v1.0.1
```

ou, sur GitHub, onglet **Actions → Release → Run workflow** en donnant le numéro de version. Le workflow compile et
teste `Digital.jar`, fabrique le `.dmg` (macOS arm64), le `.msi` et le `.zip` portable (Windows), le `.deb` et le
`.tar.gz` (Linux), puis crée la release avec le texte de [RELEASE_NOTES.md](RELEASE_NOTES.md).

`icons/` contient l'icône de l'application aux formats `.icns` (macOS), `.ico` (Windows) et `.png` (Linux), tirée de
`src/main/svg/icon.svg` (rendu 1024 px sur une tuile blanche arrondie).

Construire localement le paquet du système courant (JDK 17+ avec `jpackage`) :

```
mvn -P no-git-rev -Dgit.commit.id.describe=v1.0.0 -DskipTests package
mkdir -p input && cp target/Digital.jar input/
jpackage --type app-image --name DigitalQuartus --app-version 1.0.0 --input input \
  --main-jar Digital.jar --main-class de.neemann.digital.gui.Main --icon packaging/icons/icon.png
```
