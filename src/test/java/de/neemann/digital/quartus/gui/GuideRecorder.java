/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus.gui;

import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.elements.VisualElement;
import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.draw.shapes.ShapeFactory;
import de.neemann.digital.gui.Main;
import de.neemann.digital.quartus.Lab;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Records the screenshots of the animations of the built-in guide (menu Help > Guide).
 * It drives the application like a user and stores the frames, their captions and the place of the mouse
 * in a folder for every scene. The tool outils/guide/make_gifs.py makes the animated GIFs from them.
 * <p>
 * Usage, in a virtual display and with the French user interface:
 * <pre>
 * xvfb-run -s "-screen 0 1400x900x24" java -Duser.language=fr -cp target/Digital.jar:target/test-classes \
 *     de.neemann.digital.quartus.gui.GuideRecorder target/guide
 * </pre>
 */
public final class GuideRecorder {
    private final File out;
    private final File work;
    private PrintWriter frames;
    private File sceneDir;
    private int frameNo;

    private GuideRecorder(File out) throws IOException {
        this.out = out;
        work = Files.createTempDirectory("guide").toFile();
    }

    /**
     * Records all scenes.
     *
     * @param args the output folder
     * @throws Exception Exception
     */
    public static void main(String[] args) throws Exception {
        new GuideRecorder(new File(args.length > 0 ? args[0] : "target/guide")).record();
        System.exit(0);
    }

    private void record() throws Exception {
        File root = new File(".").getCanonicalFile();
        File projects = new File(work, "projets");
        copy(new File(root, "projets/ouex"), new File(projects, "ouex"));
        copy(new File(root, "projets/C_3_jk"), new File(projects, "C_3_jk"));
        copy(new File(root, "bibliotheque-EA"), new File(work, "bibliotheque-EA"));
        File ouex = new File(projects, "ouex/ouex.dig");

        Main.main(new String[]{ouex.getPath()});
        sleep(4000);
        Main main = main();
        edt(() -> {
            main.setBounds(0, 0, 1100, 720);
            main.getCircuitComponent().fitCircuit();
        });
        sleep(500);

        sceneOpen(main, new File(projects, "C_3_jk/C_3_jk.qpf"), ouex);
        sceneSimulation(main);
        sceneWaveform(main);
        scenePinPlanner(main);
        sceneExport(main);
        sceneLab(main, ouex);
    }

    // § 14: open a Quartus project
    private void sceneOpen(Main main, File qpf, File ouex) throws Exception {
        scene("ouvrir");
        JMenuItem item = menu(main, "Fichier", "Ouvrir un projet ou un fichier Quartus");
        frame("Fichier → Ouvrir un projet ou un fichier Quartus", 2500, item);
        closeMenus();
        edt(() -> main.open(qpf, false));
        sleep(2500);
        edt(() -> main.getCircuitComponent().fitCircuit());
        frame("Le projet est converti puis ouvert : ici sur la carte DE0, d'après le .qsf", 3500, null);
        edt(() -> main.open(ouex, false));
        sleep(1500);
        edt(() -> main.getCircuitComponent().fitCircuit());
        frame("Un circuit de l'application s'ouvre avec Fichier → Ouvrir", 2500, null);
        end();
    }

    // § 15.2: run the simulation and click on the inputs
    private void sceneSimulation(Main main) throws Exception {
        scene("simulation");
        JMenuItem run = menu(main, "Simulation", "Lancer la simulation");
        frame("Simulation → Lancer la simulation (ou ▶)", 2500, run);
        closeMenus();
        edt(run::doClick);
        sleep(1500);
        frame("E1 = 0, E2 = 0 : S = 0", 2000, null, main);
        String[][] clicks = {{"E1", "E1 = 1, E2 = 0 : S = 1"}, {"E2", "E1 = 1, E2 = 1 : S = 0"}, {"E1", "E1 = 0, E2 = 1 : S = 1"}};
        for (String[] c : clicks) {
            Point p = clickElement(main, c[0]);
            frame("Clic sur " + c[0] + " → " + c[1], 2200, p, main);
        }
        stopSimulation(main);
        end();
    }

    // § 15.1: the waveform editor
    private void sceneWaveform(Main main) throws Exception {
        scene("chronogrammes");
        JMenuItem item = menu(main, "Quartus", "Éditeur de chronogrammes");
        frame("Quartus → Éditeur de chronogrammes", 2500, item);
        closeMenus();
        edt(() -> WaveformEditor.show(main));
        sleep(1500);
        Window w = window("WaveformEditor");
        edt(() -> w.setBounds(60, 140, 1000, 330));
        sleep(800);
        clickButton(w, "↔");
        frame("E1 et E2 sont des horloges (périodes différentes) : S est calculée aussitôt", 3500, null);
        edt(() -> {
            JComboBox<?> mode = find((Container) w, JComboBox.class).get(1);
            mode.setSelectedIndex(1);
        });
        sleep(500);
        JComboBox<?> modeBox = find((Container) w, JComboBox.class).get(1);
        frame("Mode Temporelle : chaque porte a un délai (ici 10 ns)", 2500, modeBox);
        for (int i = 0; i < 4; i++) clickButton(w, "+");
        frame("En agrandissant, on voit le retard de S sur les entrées", 3500, null);
        edt(() -> {
            modeBox.setSelectedIndex(0);
            w.dispose();
        });
        sleep(500);
        end();
    }

    // § 16: the pin planner and the virtual board
    private void scenePinPlanner(Main main) throws Exception {
        scene("pinplanner");
        JMenuItem item = menu(main, "Quartus", "Pin Planner (affectation des broches)");
        frame("Quartus → Pin Planner", 2500, item);
        closeMenus();
        SwingUtilities.invokeLater(() -> PinPlannerDialog.show(main));
        sleep(1500);
        Window d = window("PinPlannerDialog");
        edt(() -> d.setLocation(20, 60));
        sleep(500);
        JTable t = find((Container) d, JTable.class).get(0);
        Component boardPanel = null;
        for (Component c : find((Container) d, Component.class))
            if (c.getClass().getSimpleName().equals("BoardPanel"))
                boardPanel = c;
        // the places of SW0, SW1 and LEDG0 in the drawing of the board, see PinPlannerDialog
        int[][] places = {{40 + 9 * 56 + 22, 312}, {40 + 8 * 56 + 22, 312}, {40 + 9 * 56 + 22, 235}};
        String[] text = {"E1 → SW0 (J6)", "E2 → SW1 (H5)", "S → LEDG0 (J1)"};
        for (int i = 0; i < text.length; i++) {
            Point row = click(center(cell(t, i)));
            frame("Choisir le signal " + text[i].substring(0, 2).trim() + " dans la liste…", 1400, row, d);
            Point o = boardPanel.getLocationOnScreen();
            Point res = click(new Point(o.x + places[i][0], o.y + places[i][1]));
            frame("… puis cliquer sur la ressource de la carte : " + text[i], 2000, res, d);
        }
        JButton show = button(d, "Afficher sur la carte DE0");
        frame("Afficher sur la carte DE0", 2000, center(show), d);
        edt(show::doClick);
        sleep(3000);
        Main board = null;
        for (Window w : Window.getWindows())
            if (w instanceof Main && w != main && w.isVisible()) board = (Main) w;
        if (board != null) {
            Main b = board;
            edt(() -> {
                b.setBounds(30, 30, 1100, 720);
                b.getCircuitComponent().fitCircuit();
                b.toFront();
            });
            sleep(800);
            frame("La carte DE0 virtuelle : SW0 et SW1 commandent LEDG0", 2500, null, b);
            JMenuItem run = menu(b, "Simulation", "Lancer la simulation");
            closeMenus();
            edt(run::doClick);
            sleep(1500);
            String[][] clicks = {{"SW0", "SW0 = 1, SW1 = 0 : LEDG0 allumée"}, {"SW1", "SW0 = 1, SW1 = 1 : LEDG0 éteinte"},
                    {"SW0", "SW0 = 0, SW1 = 1 : LEDG0 allumée"}};
            for (String[] c : clicks) {
                Point p = clickElement(b, c[0]);
                frame("Clic sur " + c[0] + " → " + c[1], 2200, p, b);
            }
            stopSimulation(b);
            edt(b::dispose);
        }
        sleep(500);
        end();
    }

    // § 17: export to Quartus
    private void sceneExport(Main main) throws Exception {
        scene("export");
        edt(main::toFront);
        sleep(500);
        JMenuItem item = menu(main, "Fichier", "Exporter", "Exporter vers Quartus (bdf)");
        frame("Fichier → Exporter → Exporter vers Quartus (bdf)", 3500, item);
        closeMenus();
        frame("Le .bdf et le projet (.qpf/.qsf avec les broches) s'ouvrent dans Quartus sur le PC de l'école", 3000, null);
        end();
    }

    // the labs which grade themselves
    private void sceneLab(Main main, File ouex) throws Exception {
        scene("tp");
        ElementLibrary library = new ElementLibrary();
        ShapeFactory sf = new ShapeFactory(library);
        Circuit solution = Circuit.loadCircuit(ouex, sf);
        Lab lab = new Lab("TP : OU exclusif", "Réalisez S = E1 ⊕ E2, puis Quartus → Vérifier mon TP.", true, Lab.testsOf(solution));
        // a student who used an OR gate instead of a XOR gate
        Circuit wrong = Lab.createLabCircuit(solution, lab, false, sf);
        for (VisualElement ve : new ArrayList<>(wrong.getElements()))
            if (ve.getElementName().equals("XOr")) {
                wrong.delete(ve);
                wrong.add(new VisualElement("Or").setPos(ve.getPos()).setShapeFactory(sf));
            }
        File dir = new File(work, "tp");
        dir.mkdirs();
        File wrongFile = new File(dir, "ouex_TP.dig");
        wrong.save(wrongFile);
        edt(() -> main.open(wrongFile, false));
        sleep(1500);
        edt(() -> main.getCircuitComponent().fitCircuit());
        frame("Un TP donné par l'enseignant : l'étudiant a mis une porte OU", 2500, null);
        JMenuItem item = menu(main, "Quartus", "Vérifier mon TP");
        frame("Quartus → Vérifier mon TP", 2000, item);
        closeMenus();
        SwingUtilities.invokeLater(() -> LabDialogs.check(main));
        sleep(1500);
        frame("Les tests cachés trouvent l'erreur et montrent la première ligne fausse", 4000, null);
        closeDialogs();

        Circuit right = Lab.createLabCircuit(solution, lab, false, sf);
        File rightFile = new File(dir, "ouex_TP_corrige.dig");
        right.save(rightFile);
        edt(() -> main.open(rightFile, false));
        sleep(1500);
        edt(() -> main.getCircuitComponent().fitCircuit());
        SwingUtilities.invokeLater(() -> LabDialogs.check(main));
        sleep(1500);
        frame("Avec une porte OU exclusif, tous les tests passent", 3500, null);
        closeDialogs();
        end();
    }

    private void stopSimulation(Main main) throws Exception {
        edt(main::ensureModelIsStopped);
        sleep(500);
    }

    private void scene(String name) throws IOException {
        sceneDir = new File(out, name);
        sceneDir.mkdirs();
        frames = new PrintWriter(new FileWriter(new File(sceneDir, "frames.txt")));
        frameNo = 0;
    }

    private void end() {
        frames.close();
    }

    /* stores a frame: the visible windows, the caption and the place of the mouse pointer */
    private void frame(String caption, int duration, Component pointer) throws Exception {
        Point p = null;
        if (pointer != null && pointer.isShowing()) {
            Point l = pointer.getLocationOnScreen();
            p = new Point(l.x + Math.min(pointer.getWidth() / 2, 60), l.y + pointer.getHeight() / 2);
        }
        frame(caption, duration, p, null);
    }

    /* stores a frame of the given window, or of all visible windows if null; the pointer is given on the screen */
    private void frame(String caption, int duration, Point pointer, Window only) throws Exception {
        sleep(600);
        Rectangle r = only == null ? null : only.getBounds();
        if (r == null)
            for (Window w : Window.getWindows())
                if (w.isShowing()) r = r == null ? w.getBounds() : r.union(w.getBounds());
        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        Rectangle area = r == null ? screen : r.intersection(screen);
        String name = String.format("%02d.png", frameNo++);
        ImageIO.write(new Robot().createScreenCapture(area), "png", new File(sceneDir, name));
        int x = pointer == null ? -1 : pointer.x - area.x;
        int y = pointer == null ? -1 : pointer.y - area.y;
        frames.println(name + "|" + duration + "|" + x + "|" + y + "|" + caption);
    }

    /* clicks with the mouse at the given place on the screen */
    private Point click(Point p) throws Exception {
        Robot robot = new Robot();
        robot.mouseMove(p.x, p.y);
        sleep(200);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        sleep(80);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        sleep(700);
        return p;
    }

    /* clicks with the mouse on the element with the given label, returns the place on the screen */
    private Point clickElement(Main main, String label) throws Exception {
        Point[] p = new Point[1];
        edt(() -> {
            for (VisualElement ve : main.getCircuitComponent().getCircuit().getElements())
                if (label.equals(ve.getElementAttributes().getLabel())) {
                    de.neemann.digital.draw.graphics.GraphicMinMax mm = ve.getMinMax(false);
                    Point c = main.getCircuitComponent().transform(new de.neemann.digital.draw.graphics.Vector(
                            (mm.getMin().x + mm.getMax().x) / 2, (mm.getMin().y + mm.getMax().y) / 2));
                    Point s = main.getCircuitComponent().getLocationOnScreen();
                    p[0] = new Point(s.x + c.x, s.y + c.y);
                }
        });
        if (p[0] == null)
            throw new RuntimeException("element not found: " + label);
        return click(p[0]);
    }

    /* opens the menus and selects the item, the item is returned */
    private JMenuItem menu(JFrame frame, String... path) throws Exception {
        List<MenuElement> elements = new ArrayList<>();
        JMenuItem[] last = new JMenuItem[1];
        edt(() -> {
            JMenuBar bar = frame.getJMenuBar();
            elements.add(bar);
            MenuElement parent = bar;
            for (String name : path) {
                JMenuItem found = null;
                for (MenuElement e : parent.getSubElements()) {
                    MenuElement c = e instanceof JPopupMenu ? e : null;
                    if (c == null && e instanceof JMenuItem && ((JMenuItem) e).getText().equals(name))
                        found = (JMenuItem) e;
                    if (c != null)
                        for (MenuElement e2 : c.getSubElements())
                            if (e2 instanceof JMenuItem && ((JMenuItem) e2).getText().equals(name))
                                found = (JMenuItem) e2;
                }
                if (found == null)
                    throw new RuntimeException("menu item not found: " + name);
                elements.add(found);
                if (found instanceof JMenu) {
                    elements.add(((JMenu) found).getPopupMenu());
                    parent = ((JMenu) found).getPopupMenu();
                }
                last[0] = found;
            }
            MenuSelectionManager.defaultManager().setSelectedPath(elements.toArray(new MenuElement[0]));
        });
        sleep(800);
        return last[0];
    }

    private void closeMenus() throws Exception {
        edt(() -> MenuSelectionManager.defaultManager().clearSelectedPath());
        sleep(400);
    }

    private void closeDialogs() throws Exception {
        edt(() -> {
            for (Window w : Window.getWindows())
                if (w instanceof JDialog && w.isVisible())
                    w.dispose();
        });
        sleep(500);
    }

    private void clickButton(Window w, String text) throws Exception {
        JButton b = button(w, text);
        edt(b::doClick);
        sleep(300);
    }

    private static JButton button(Window w, String text) {
        for (JButton b : find((Container) w, JButton.class))
            if (text.equals(b.getText()))
                return b;
        throw new RuntimeException("button not found: " + text);
    }

    private static Point center(Component c) {
        Point l = c.getLocationOnScreen();
        return new Point(l.x + Math.min(c.getWidth() / 2, 60), l.y + c.getHeight() / 2);
    }

    private static Component cell(JTable t, int row) {
        Rectangle r = t.getCellRect(row, 0, true);
        // a dummy component placed on the cell to calculate its position
        return new Component() {
            @Override
            public Point getLocationOnScreen() {
                Point p = t.getLocationOnScreen();
                return new Point(p.x + r.x, p.y + r.y);
            }

            @Override
            public boolean isShowing() {
                return true;
            }

            @Override
            public int getWidth() {
                return r.width;
            }

            @Override
            public int getHeight() {
                return r.height;
            }
        };
    }

    private static Main main() {
        for (Window w : Window.getWindows())
            if (w instanceof Main && w.isVisible())
                return (Main) w;
        throw new RuntimeException("no main window");
    }

    private static Window window(String className) {
        for (Window w : Window.getWindows())
            if (w.isVisible() && w.getClass().getSimpleName().equals(className))
                return w;
        throw new RuntimeException("window not found: " + className);
    }

    static <T> List<T> find(Container c, Class<T> cl) {
        List<T> l = new ArrayList<>();
        for (Component x : c.getComponents()) {
            if (cl.isInstance(x)) l.add(cl.cast(x));
            if (x instanceof Container) l.addAll(find((Container) x, cl));
        }
        return l;
    }

    private static void edt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static void sleep(int ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    private static void copy(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (from.getName().equals("digital") || from.getName().equals("db")) return;
            to.mkdirs();
            File[] files = from.listFiles();
            if (files != null)
                for (File f : files)
                    copy(f, new File(to, f.getName()));
        } else
            Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
