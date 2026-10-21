/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import de.neemann.digital.core.ObservableValue;
import de.neemann.digital.core.ObservableValues;
import de.neemann.digital.core.extern.PortDefinition;
import de.neemann.digital.core.extern.handler.ProcessInterface;
import junit.framework.TestCase;

import java.io.IOException;

public class VhdlInterpreterTest extends TestCase {
    private static final String HEADER = "library ieee;\n"
            + "use ieee.std_logic_1164.all;\n"
            + "use ieee.numeric_std.all;\n";

    private static final String COUNTER = HEADER
            + "entity counter is\n"
            + "  port (clk, en : in std_logic; q : out std_logic_vector(3 downto 0); carry : out std_logic);\n"
            + "end counter;\n"
            + "architecture rtl of counter is\n"
            + "  signal c : unsigned(3 downto 0) := (others => '0');\n"
            + "begin\n"
            + "  process (clk)\n"
            + "  begin\n"
            + "    if rising_edge(clk) then\n"
            + "      if en = '1' then\n"
            + "        if c = 9 then c <= (others => '0'); else c <= c + 1; end if;\n"
            + "      end if;\n"
            + "    end if;\n"
            + "  end process;\n"
            + "  q <= std_logic_vector(c);\n"
            + "  carry <= '1' when c = 9 else '0';\n"
            + "end rtl;\n";

    private static HdlTestSim sim(String entity, String code, String inputs, String outputs) throws IOException {
        assertNull(VhdlInterpreter.check(entity, code));
        return new HdlTestSim(VhdlInterpreter::start, entity, code, inputs, outputs);
    }

    public void testCounter() throws Exception {
        assertNull(VhdlInterpreter.check("counter", COUNTER));
        PortDefinition in = new PortDefinition("clk,en");
        PortDefinition out = new PortDefinition("q:4,carry");
        ProcessInterface p = VhdlInterpreter.start("counter", COUNTER, in, out);
        ObservableValue clk = new ObservableValue("clk", 1);
        ObservableValue en = new ObservableValue("en", 1).setValue(1);
        ObservableValues ins = new ObservableValues(clk, en);
        ObservableValues outs = out.createOutputs();
        for (int i = 1; i <= 25; i++) {
            clk.setValue(1);
            p.writeValues(ins);
            clk.setValue(0);
            p.writeValues(ins);
            p.readValues(outs);
            assertEquals(i % 10, outs.get(0).getValue());
            assertEquals(i % 10 == 9 ? 1 : 0, outs.get(1).getValue());
        }
    }

    public void testUnsupported() {
        String code = "entity top is port (a : in std_logic; y : out std_logic); end top;\n"
                + "architecture s of top is\n"
                + "  component inv port (i : in std_logic; o : out std_logic); end component;\n"
                + "begin\n"
                + "  u1 : inv port map (a, y);\n"
                + "end s;\n";
        String msg = VhdlInterpreter.check("top", code);
        assertNotNull(msg);
        assertTrue(msg, msg.contains("inv"));

        String rec = HEADER + "entity r is port (a : in std_logic; y : out std_logic); end r;\n"
                + "architecture s of r is\n"
                + "  type pair is record x, y : std_logic; end record;\n"
                + "begin\n"
                + "  y <= a;\n"
                + "end s;\n";
        msg = VhdlInterpreter.check("r", rec);
        assertNotNull(msg);
        assertTrue(msg, msg.contains("record"));
    }

    public void testStateMachine() throws Exception {
        // a Moore machine which detects the sequence 1,0,1 with an asynchronous reset
        String code = HEADER
                + "entity detect is\n"
                + "  port (clk, rst, x : in std_logic; y : out std_logic; st : out std_logic_vector(1 downto 0));\n"
                + "end entity detect;\n"
                + "architecture fsm of detect is\n"
                + "  type state_t is (idle, got1, got10, found);\n"
                + "  signal state, nxt : state_t;\n"
                + "begin\n"
                + "  reg: process (clk, rst)\n"
                + "  begin\n"
                + "    if rst = '1' then\n"
                + "      state <= idle;\n"
                + "    elsif clk'event and clk = '1' then\n"
                + "      state <= nxt;\n"
                + "    end if;\n"
                + "  end process reg;\n"
                + "  comb: process (state, x)\n"
                + "  begin\n"
                + "    nxt <= state;\n"
                + "    case state is\n"
                + "      when idle => if x = '1' then nxt <= got1; end if;\n"
                + "      when got1 => if x = '0' then nxt <= got10; end if;\n"
                + "      when got10 => if x = '1' then nxt <= found; else nxt <= idle; end if;\n"
                + "      when found => if x = '1' then nxt <= got1; else nxt <= got10; end if;\n"
                + "    end case;\n"
                + "  end process comb;\n"
                + "  y <= '1' when state = found else '0';\n"
                + "  st <= std_logic_vector(to_unsigned(state_t'pos(state), 2));\n"
                + "end architecture fsm;\n";
        HdlTestSim s = sim("detect", code, "clk,rst,x", "y,st:2");
        s.set("rst", 1).step().set("rst", 0).step();
        int[] in = {1, 0, 1, 0, 1, 1, 0, 0, 1};
        int[] y = {0, 0, 1, 0, 1, 0, 0, 0, 0};
        for (int i = 0; i < in.length; i++) {
            s.set("x", in[i]).step().clock("clk");
            assertEquals("step " + i, y[i], s.get("y"));
        }
        assertEquals(1, s.get("st"));
        s.set("rst", 1).step();
        assertEquals(0, s.get("st"));
    }

    private static final String GENERIC_COUNTER = HEADER
            + "entity gcount is\n"
            + "  generic (WIDTH : positive := 8; MAX : natural := 255);\n"
            + "  port (clk, reset : in std_logic; q : out std_logic_vector(WIDTH - 1 downto 0); tc : out std_logic);\n"
            + "end gcount;\n"
            + "architecture rtl of gcount is\n"
            + "  signal cnt : integer range 0 to MAX := 0;\n"
            + "begin\n"
            + "  process\n"
            + "  begin\n"
            + "    wait until rising_edge(clk);\n"
            + "    if reset = '1' or cnt = MAX then cnt <= 0; else cnt <= cnt + 1; end if;\n"
            + "  end process;\n"
            + "  q <= std_logic_vector(to_unsigned(cnt, q'length));\n"
            + "  tc <= '1' when cnt = MAX else '0';\n"
            + "end rtl;\n";

    public void testGenericsAndInstances() throws Exception {
        // the default values of the generics
        HdlTestSim s = sim("gcount", GENERIC_COUNTER, "clk,reset", "q:8,tc");
        for (int i = 1; i <= 300; i++) {
            s.clock("clk");
            assertEquals(i % 256, s.get("q"));
        }

        // two instances with different generic values, named and positional associations
        String top = GENERIC_COUNTER
                + "library ieee; use ieee.std_logic_1164.all;\n"
                + "entity top is\n"
                + "  port (clk : in std_logic; a : out std_logic_vector(2 downto 0); b : out std_logic_vector(3 downto 0); t : out std_logic);\n"
                + "end top;\n"
                + "architecture s of top is\n"
                + "  component gcount is\n"
                + "    generic (WIDTH : positive := 8; MAX : natural := 255);\n"
                + "    port (clk, reset : in std_logic; q : out std_logic_vector(WIDTH - 1 downto 0); tc : out std_logic);\n"
                + "  end component;\n"
                + "  signal t1, t2 : std_logic;\n"
                + "begin\n"
                + "  u1 : gcount generic map (WIDTH => 3, MAX => 5) port map (clk => clk, reset => '0', q => a, tc => t1);\n"
                + "  u2 : entity work.gcount(rtl) generic map (4, 9) port map (clk, '0', b, t2);\n"
                + "  t <= t1 and t2;\n"
                + "end s;\n";
        s = sim("top", top, "clk", "a:3,b:4,t");
        for (int i = 1; i <= 40; i++) {
            s.clock("clk");
            assertEquals(i % 6, s.get("a"));
            assertEquals(i % 10, s.get("b"));
            assertEquals(i % 30 == 29 ? 1 : 0, s.get("t"));
        }
    }

    public void testRippleCarryAdderWithGenerate() throws Exception {
        String code = HEADER
                + "entity fa is\n"
                + "  port (a, b, ci : in std_logic; s, co : out std_logic);\n"
                + "end fa;\n"
                + "architecture df of fa is\n"
                + "begin\n"
                + "  s <= a xor b xor ci;\n"
                + "  co <= (a and b) or (ci and (a xor b));\n"
                + "end df;\n"
                + "library ieee; use ieee.std_logic_1164.all;\n"
                + "entity adder is\n"
                + "  generic (N : integer := 4);\n"
                + "  port (a, b : in std_logic_vector(N-1 downto 0); cin : in std_logic;\n"
                + "        sum : out std_logic_vector(N-1 downto 0); cout : out std_logic);\n"
                + "end adder;\n"
                + "architecture struct of adder is\n"
                + "  signal c : std_logic_vector(N downto 0);\n"
                + "begin\n"
                + "  c(0) <= cin;\n"
                + "  gen: for i in 0 to N-1 generate\n"
                + "    stage: entity work.fa port map (a => a(i), b => b(i), ci => c(i), s => sum(i), co => c(i+1));\n"
                + "  end generate gen;\n"
                + "  cout <= c(N);\n"
                + "end struct;\n";
        HdlTestSim s = sim("adder", code, "a:4,b:4,cin", "sum:4,cout");
        for (int a = 0; a < 16; a++)
            for (int b = 0; b < 16; b++)
                for (int c = 0; c < 2; c++) {
                    s.set("a", a).set("b", b).set("cin", c).step();
                    int r = a + b + c;
                    assertEquals(r & 15, s.get("sum"));
                    assertEquals(r >> 4, s.get("cout"));
                }
    }

    public void testSevenSegmentRomAndPackage() throws Exception {
        String code = HEADER
                + "package seg_pkg is\n"
                + "  subtype seg_t is std_logic_vector(6 downto 0);\n"
                + "  type rom_t is array (0 to 15) of seg_t;\n"
                + "  constant SEGMENTS : rom_t := (\"0111111\", \"0000110\", \"1011011\", \"1001111\", \"1100110\", \"1101101\",\n"
                + "    \"1111101\", \"0000111\", \"1111111\", \"1101111\", others => \"1000000\");\n"
                + "  function invert(v : std_logic_vector) return std_logic_vector;\n"
                + "end package seg_pkg;\n"
                + "package body seg_pkg is\n"
                + "  function invert(v : std_logic_vector) return std_logic_vector is\n"
                + "    variable r : std_logic_vector(v'range);\n"
                + "  begin\n"
                + "    for i in v'range loop\n"
                + "      r(i) := not v(i);\n"
                + "    end loop;\n"
                + "    return r;\n"
                + "  end function;\n"
                + "end package body;\n"
                + "library ieee; use ieee.std_logic_1164.all; use ieee.numeric_std.all;\n"
                + "use work.seg_pkg.all;\n"
                + "entity seg7 is\n"
                + "  port (d : in std_logic_vector(3 downto 0); active_low : in std_logic; seg : out seg_t);\n"
                + "end seg7;\n"
                + "architecture rom of seg7 is\n"
                + "  signal raw : seg_t;\n"
                + "begin\n"
                + "  raw <= SEGMENTS(to_integer(unsigned(d)));\n"
                + "  seg <= invert(raw) when active_low = '1' else raw;\n"
                + "end rom;\n";
        int[] expected = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40};
        HdlTestSim s = sim("seg7", code, "d:4,active_low", "seg:7");
        for (int d = 0; d < 16; d++) {
            s.set("d", d).set("active_low", 0).step();
            assertEquals(expected[d], s.get("seg"));
            s.set("active_low", 1).step();
            assertEquals(~expected[d] & 0x7F, s.get("seg"));
        }
    }

    public void testSelectedAssignmentAndCaseDecoder() throws Exception {
        String code = HEADER
                + "entity dec is\n"
                + "  port (s : in std_logic_vector(1 downto 0); a, b, c, d : in std_logic;\n"
                + "        y : out std_logic; onehot : out std_logic_vector(3 downto 0); range_out : out std_logic);\n"
                + "end dec;\n"
                + "architecture rtl of dec is\n"
                + "  signal n : integer range 0 to 3;\n"
                + "begin\n"
                + "  with s select y <= a when \"00\", b when \"01\", c when \"10\", d when others;\n"
                + "  n <= to_integer(unsigned(s));\n"
                + "  process (all)\n"
                + "  begin\n"
                + "    onehot <= (others => '0');\n"
                + "    onehot(n) <= '1';\n"
                + "    case n is\n"
                + "      when 0 to 1 => range_out <= '0';\n"
                + "      when others => range_out <= '1';\n"
                + "    end case;\n"
                + "  end process;\n"
                + "end rtl;\n";
        HdlTestSim s = sim("dec", code, "s:2,a,b,c,d", "y,onehot:4,range_out");
        s.set("a", 1).set("b", 0).set("c", 1).set("d", 0);
        int[] y = {1, 0, 1, 0};
        for (int i = 0; i < 4; i++) {
            s.set("s", i).step();
            assertEquals(y[i], s.get("y"));
            assertEquals(1 << i, s.get("onehot"));
            assertEquals(i >= 2 ? 1 : 0, s.get("range_out"));
        }
    }

    public void testLoopsAndVariables() throws Exception {
        // population count, index of the highest set bit with EXIT, bit reversal
        String code = HEADER
                + "entity bits is\n"
                + "  port (d : in std_logic_vector(7 downto 0); ones : out unsigned(3 downto 0);\n"
                + "        msb : out integer range 0 to 7; rev : out std_logic_vector(7 downto 0));\n"
                + "end bits;\n"
                + "architecture beh of bits is\n"
                + "begin\n"
                + "  process (d)\n"
                + "    variable cnt : unsigned(3 downto 0);\n"
                + "    variable hi : integer range 0 to 7;\n"
                + "  begin\n"
                + "    cnt := (others => '0');\n"
                + "    for i in d'range loop\n"
                + "      if d(i) = '1' then cnt := cnt + 1; end if;\n"
                + "    end loop;\n"
                + "    ones <= cnt;\n"
                + "    hi := 0;\n"
                + "    search: for i in 7 downto 0 loop\n"
                + "      next search when d(i) = '0';\n"
                + "      hi := i;\n"
                + "      exit search;\n"
                + "    end loop;\n"
                + "    msb <= hi;\n"
                + "    for i in 0 to 7 loop\n"
                + "      rev(i) <= d(7 - i);\n"
                + "    end loop;\n"
                + "  end process;\n"
                + "end beh;\n";
        HdlTestSim s = sim("bits", code, "d:8", "ones:4,msb:3,rev:8");
        for (int d = 0; d < 256; d++) {
            s.set("d", d).step();
            assertEquals(Integer.bitCount(d), s.get("ones"));
            assertEquals(d == 0 ? 0 : 31 - Integer.numberOfLeadingZeros(d), s.get("msb"));
            assertEquals(Integer.reverse(d) >>> 24, s.get("rev"));
        }
    }

    public void testShiftRegisterAndOperators() throws Exception {
        String code = HEADER
                + "entity shift is\n"
                + "  port (clk, din : in std_logic; q : out std_logic_vector(3 downto 0);\n"
                + "        a : in signed(3 downto 0); wide : out signed(7 downto 0); sr : out signed(3 downto 0);\n"
                + "        sl : out std_logic_vector(3 downto 0); cat : out std_logic_vector(5 downto 0);\n"
                + "        agg : out std_logic_vector(7 downto 0); len : out integer range 0 to 15);\n"
                + "end shift;\n"
                + "architecture rtl of shift is\n"
                + "  signal r : std_logic_vector(3 downto 0) := \"0000\";\n"
                + "  constant ONE : std_logic_vector(3 downto 0) := x\"1\";\n"
                + "  alias top_bit : std_logic is r(3);\n"
                + "begin\n"
                + "  process (clk)\n"
                + "  begin\n"
                + "    if rising_edge(clk) then\n"
                + "      r <= r(2 downto 0) & din;\n"
                + "    end if;\n"
                + "  end process;\n"
                + "  q <= r;\n"
                + "  wide <= resize(a, 8);\n"
                + "  sr <= shift_right(a, 1);\n"
                + "  sl <= std_logic_vector(unsigned(ONE) sll 2);\n"
                + "  cat <= top_bit & \"10\" & r(1 downto 0) & '1';\n"
                + "  agg <= (7 => '1', 2 downto 1 => din, others => '0');\n"
                + "  len <= r'length + agg'high;\n"
                + "end rtl;\n";
        HdlTestSim s = sim("shift", code, "clk,din,a:4", "q:4,wide:8,sr:4,sl:4,cat:6,agg:8,len:4");
        int[] in = {1, 0, 1, 1, 0};
        int r = 0;
        for (int b : in) {
            s.set("din", b).step().clock("clk");
            r = ((r << 1) | b) & 15;
            assertEquals(r, s.get("q"));
            assertEquals(((r >> 3) << 5) | (2 << 3) | ((r & 3) << 1) | 1, s.get("cat"));
            assertEquals(0x80 | (b == 1 ? 6 : 0), s.get("agg"));
        }
        s.set("a", 0b1010).step(); // -6
        assertEquals(0xFA, s.get("wide"));
        assertEquals(0b1101, s.get("sr"));
        assertEquals(4, s.get("sl"));
        assertEquals(11, s.get("len"));
    }

    public void testRegisterFileAndIfGenerate() throws Exception {
        String code = HEADER
                + "entity regfile is\n"
                + "  generic (REGISTERED : boolean := true);\n"
                + "  port (clk, we : in std_logic; wa, ra : in unsigned(1 downto 0);\n"
                + "        wd : in std_logic_vector(7 downto 0); rd : out std_logic_vector(7 downto 0));\n"
                + "end regfile;\n"
                + "architecture rtl of regfile is\n"
                + "  type mem_t is array (natural range <>) of std_logic_vector(7 downto 0);\n"
                + "  signal mem : mem_t(0 to 3) := (others => (others => '0'));\n"
                + "begin\n"
                + "  process (clk)\n"
                + "  begin\n"
                + "    if rising_edge(clk) then\n"
                + "      if we = '1' then mem(to_integer(wa)) <= wd; end if;\n"
                + "    end if;\n"
                + "  end process;\n"
                + "  g1: if REGISTERED generate\n"
                + "    rd <= mem(to_integer(ra));\n"
                + "  end generate;\n"
                + "  g2: if not REGISTERED generate\n"
                + "    rd <= (others => '1');\n"
                + "  end generate;\n"
                + "end rtl;\n";
        HdlTestSim s = sim("regfile", code, "clk,we,wa:2,ra:2,wd:8", "rd:8");
        s.set("we", 1);
        for (int a = 0; a < 4; a++)
            s.set("wa", a).set("wd", 10 * a + 3).step().clock("clk");
        s.set("we", 0);
        for (int a = 0; a < 4; a++) {
            s.set("ra", a).step();
            assertEquals(10 * a + 3, s.get("rd"));
        }
    }

    public void testFunctionInArchitecture() throws Exception {
        String code = HEADER
                + "entity gray is\n"
                + "  port (b : in std_logic_vector(3 downto 0); g : out std_logic_vector(3 downto 0); p : out std_logic;\n"
                + "        mx : out unsigned(3 downto 0));\n"
                + "end gray;\n"
                + "architecture rtl of gray is\n"
                + "  function parity(v : std_logic_vector) return std_logic is\n"
                + "    variable r : std_logic := '0';\n"
                + "  begin\n"
                + "    for i in v'low to v'high loop r := r xor v(i); end loop;\n"
                + "    return r;\n"
                + "  end parity;\n"
                + "  function max(a, b : unsigned) return unsigned is\n"
                + "  begin\n"
                + "    if a > b then return a; else return b; end if;\n"
                + "  end function max;\n"
                + "begin\n"
                + "  g <= b xor ('0' & b(3 downto 1));\n"
                + "  p <= parity(b);\n"
                + "  mx <= max(unsigned(b), \"0101\");\n"
                + "end rtl;\n";
        HdlTestSim s = sim("gray", code, "b:4", "g:4,p,mx:4");
        for (int b = 0; b < 16; b++) {
            s.set("b", b).step();
            assertEquals(b ^ (b >> 1), s.get("g"));
            assertEquals(Integer.bitCount(b) & 1, s.get("p"));
            assertEquals(Math.max(b, 5), s.get("mx"));
        }
    }

    public void testStdLogicUnsigned() throws Exception {
        // the old Synopsys packages are still used in many courses
        String code = "library ieee;\n"
                + "use ieee.std_logic_1164.all;\n"
                + "use ieee.std_logic_arith.all;\n"
                + "use ieee.std_logic_unsigned.all;\n"
                + "entity cnt is\n"
                + "  port (clk, clr : in std_logic; q : out std_logic_vector(3 downto 0); n : out integer range 0 to 15);\n"
                + "end cnt;\n"
                + "architecture a of cnt is\n"
                + "  signal c : std_logic_vector(3 downto 0);\n"
                + "begin\n"
                + "  process (clk, clr)\n"
                + "  begin\n"
                + "    if clr = '1' then c <= conv_std_logic_vector(0, 4);\n"
                + "    elsif clk = '1' and clk'event then c <= c + '1';\n"
                + "    end if;\n"
                + "  end process;\n"
                + "  q <= c;\n"
                + "  n <= conv_integer(c);\n"
                + "end a;\n";
        HdlTestSim s = sim("cnt", code, "clk,clr", "q:4,n:4");
        s.set("clr", 1).step().set("clr", 0).step();
        for (int i = 1; i < 20; i++) {
            s.clock("clk");
            assertEquals(i & 15, s.get("q"));
            assertEquals(i & 15, s.get("n"));
        }
    }
}
