/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import junit.framework.TestCase;

import java.io.IOException;

public class VerilogInterpreterTest extends TestCase {

    private static HdlTestSim sim(String module, String code, String inputs, String outputs) throws IOException {
        assertNull(VerilogInterpreter.check(module, code));
        return new HdlTestSim(VerilogInterpreter::start, module, code, inputs, outputs);
    }

    public void testCombinational() throws Exception {
        String code = "module logic_ops(input [3:0] a, input [3:0] b, output [3:0] y_and, output y_red, output [4:0] sum,\n"
                + "                 output eq, output lt, output [3:0] sel, output [7:0] cat, output [3:0] nshift);\n"
                + "  assign y_and = a & ~b;\n"
                + "  assign y_red = ^a | &b;\n"
                + "  assign sum = a + b;            // the carry is kept by the context width\n"
                + "  assign eq = (a == b) && !(a != b);\n"
                + "  assign lt = a < b;\n"
                + "  assign sel = a[3] ? b : {a[1:0], 2'b01};\n"
                + "  assign cat = {2{a[1:0]}, b[3 -: 2], b[0 +: 2]};\n"
                + "  assign nshift = (a << 1) >> 2;\n"
                + "endmodule\n";
        HdlTestSim s = sim("logic_ops", code, "a:4,b:4", "y_and:4,y_red,sum:5,eq,lt,sel:4,cat:8,nshift:4");
        for (int a = 0; a < 16; a++)
            for (int b = 0; b < 16; b++) {
                s.set("a", a).set("b", b).step();
                assertEquals(a & ~b & 15, s.get("y_and"));
                assertEquals((Integer.bitCount(a) & 1) | (b == 15 ? 1 : 0), s.get("y_red"));
                assertEquals(a + b, s.get("sum"));
                assertEquals(a == b ? 1 : 0, s.get("eq"));
                assertEquals(a < b ? 1 : 0, s.get("lt"));
                assertEquals((a & 8) != 0 ? b : ((a & 3) << 2) | 1, s.get("sel"));
                assertEquals(((a & 3) << 6) | ((a & 3) << 4) | ((b >> 2) << 2) | (b & 3), s.get("cat"));
                assertEquals(((a << 1) & 15) >> 2, s.get("nshift"));
            }
    }

    public void testMuxWithCase() throws Exception {
        // non-ANSI ports, always @(a or b ...), case with default, casez
        String code = "module mux4(s, a, b, c, d, y, prio);\n"
                + "  input [1:0] s;\n"
                + "  input [7:0] a, b, c, d;\n"
                + "  output [7:0] y;\n"
                + "  output [1:0] prio;\n"
                + "  reg [7:0] y;\n"
                + "  reg [1:0] prio;\n"
                + "  always @(s or a or b or c or d)\n"
                + "    case (s)\n"
                + "      2'b00: y = a;\n"
                + "      2'b01: y = b;\n"
                + "      2'd2: y = c;\n"
                + "      default: y = d;\n"
                + "    endcase\n"
                + "  always @* begin\n"
                + "    casez (a[3:0])\n"
                + "      4'b1???: prio = 2'd3;\n"
                + "      4'b01??: prio = 2'd2;\n"
                + "      4'b001?: prio = 2'd1;\n"
                + "      default: prio = 2'd0;\n"
                + "    endcase\n"
                + "  end\n"
                + "endmodule\n";
        HdlTestSim s = sim("mux4", code, "s:2,a:8,b:8,c:8,d:8", "y:8,prio:2");
        s.set("b", 22).set("c", 33).set("d", 44);
        for (int a = 0; a < 16; a++)
            for (int sel = 0; sel < 4; sel++) {
                s.set("a", a).set("s", sel).step();
                assertEquals(new int[]{a, 22, 33, 44}[sel], s.get("y"));
                assertEquals(a >= 8 ? 3 : a >= 4 ? 2 : a >= 2 ? 1 : 0, s.get("prio"));
            }
    }

    public void testCounterWithAsyncReset() throws Exception {
        String code = "`timescale 1ns / 1ps\n"
                + "module counter #(parameter WIDTH = 4) (\n"
                + "  input wire clk, input wire rst_n, input wire en,\n"
                + "  output reg [WIDTH-1:0] q, output wire wrap);\n"
                + "  always @(posedge clk or negedge rst_n) begin\n"
                + "    if (!rst_n)\n"
                + "      q <= {WIDTH{1'b0}};\n"
                + "    else if (en)\n"
                + "      q <= q + 1'b1;\n"
                + "  end\n"
                + "  assign wrap = &q;\n"
                + "endmodule\n";
        HdlTestSim s = sim("counter", code, "clk,rst_n,en", "q:4,wrap");
        s.set("rst_n", 0).step().set("rst_n", 1).set("en", 1).step();
        for (int i = 1; i <= 20; i++) {
            s.clock("clk");
            assertEquals(i & 15, s.get("q"));
            assertEquals((i & 15) == 15 ? 1 : 0, s.get("wrap"));
        }
        // the asynchronous reset works without a clock
        s.set("rst_n", 0).step();
        assertEquals(0, s.get("q"));
        s.set("rst_n", 1).set("en", 0).step().clock("clk");
        assertEquals(0, s.get("q"));
    }

    public void testStateMachine() throws Exception {
        // a traffic light with localparam state encoding and a timer
        String code = "module traffic(input clk, input reset, output reg [2:0] light);\n"
                + "  localparam RED = 2'd0, GREEN = 2'd1, YELLOW = 2'd2;\n"
                + "  reg [1:0] state, next;\n"
                + "  reg [1:0] timer;\n"
                + "  always @(posedge clk) begin\n"
                + "    if (reset) begin\n"
                + "      state <= RED;\n"
                + "      timer <= 0;\n"
                + "    end else begin\n"
                + "      if (next != state) timer <= 0; else timer <= timer + 1;\n"
                + "      state <= next;\n"
                + "    end\n"
                + "  end\n"
                + "  always @(*) begin\n"
                + "    next = state;\n"
                + "    case (state)\n"
                + "      RED: if (timer == 2) next = GREEN;\n"
                + "      GREEN: if (timer == 1) next = YELLOW;\n"
                + "      YELLOW: next = RED;\n"
                + "      default: next = RED;\n"
                + "    endcase\n"
                + "  end\n"
                + "  always @(*)\n"
                + "    case (state)\n"
                + "      RED: light = 3'b100;\n"
                + "      GREEN: light = 3'b001;\n"
                + "      default: light = 3'b010;\n"
                + "    endcase\n"
                + "endmodule\n";
        HdlTestSim s = sim("traffic", code, "clk,reset", "light:3");
        s.set("reset", 1).clock("clk").set("reset", 0).step();
        // red for 3 cycles, green for 2 cycles, yellow for 1 cycle
        int[] expected = {4, 4, 4, 1, 1, 2, 4, 4, 4, 1, 1, 2, 4};
        assertEquals(4, s.get("light"));
        for (int i = 1; i < expected.length; i++) {
            s.clock("clk");
            assertEquals("cycle " + i, expected[i], s.get("light"));
        }
    }

    public void testShiftRegisterAndMemory() throws Exception {
        String code = "module shreg(input clk, input din, input we, input [1:0] addr, output [3:0] q, output [3:0] rd);\n"
                + "  reg [3:0] r = 4'b0000;\n"
                + "  reg [3:0] mem [0:3];\n"
                + "  integer i;\n"
                + "  initial for (i = 0; i < 4; i = i + 1) mem[i] = i + 8;\n"
                + "  always @(posedge clk) begin\n"
                + "    r <= {r[2:0], din};\n"
                + "    if (we) mem[addr] <= r;\n"
                + "  end\n"
                + "  assign q = r;\n"
                + "  assign rd = mem[addr];\n"
                + "endmodule\n";
        HdlTestSim s = sim("shreg", code, "clk,din,we,addr:2", "q:4,rd:4");
        for (int a = 0; a < 4; a++) {
            s.set("addr", a).step();
            assertEquals(a + 8, s.get("rd"));
        }
        int r = 0;
        int[] bits = {1, 1, 0, 1, 0, 0, 1};
        for (int b : bits) {
            s.set("din", b).step().clock("clk");
            r = ((r << 1) | b) & 15;
            assertEquals(r, s.get("q"));
        }
        s.set("addr", 2).set("we", 1).step().clock("clk");
        assertEquals(r, s.get("rd"));
    }

    public void testSevenSegmentDecoderWithFunction() throws Exception {
        String code = "`define ACTIVE_LOW\n"
                + "module seg7(input [3:0] d, output [6:0] seg);\n"
                + "  function [6:0] decode;\n"
                + "    input [3:0] v;\n"
                + "    begin\n"
                + "      case (v)\n"
                + "        4'h0: decode = 7'h3F; 4'h1: decode = 7'h06; 4'h2: decode = 7'h5B; 4'h3: decode = 7'h4F;\n"
                + "        4'h4: decode = 7'h66; 4'h5: decode = 7'h6D; 4'h6: decode = 7'h7D; 4'h7: decode = 7'h07;\n"
                + "        4'h8: decode = 7'h7F; 4'h9: decode = 7'h6F; 4'hA: decode = 7'h77; 4'hB: decode = 7'h7C;\n"
                + "        4'hC: decode = 7'h39; 4'hD: decode = 7'h5E; 4'hE: decode = 7'h79; default: decode = 7'h71;\n"
                + "      endcase\n"
                + "    end\n"
                + "  endfunction\n"
                + "`ifdef ACTIVE_LOW\n"
                + "  assign seg = ~decode(d);\n"
                + "`else\n"
                + "  assign seg = decode(d);\n"
                + "`endif\n"
                + "endmodule\n";
        int[] expected = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F, 0x77, 0x7C, 0x39, 0x5E, 0x79, 0x71};
        HdlTestSim s = sim("seg7", code, "d:4", "seg:7");
        for (int d = 0; d < 16; d++) {
            s.set("d", d).step();
            assertEquals(~expected[d] & 0x7F, s.get("seg"));
        }
    }

    public void testParameterizedModuleAndGenerate() throws Exception {
        String code = "module full_adder(input a, input b, input ci, output s, output co);\n"
                + "  xor x1(s, a, b, ci);\n"
                + "  assign co = (a & b) | (ci & (a ^ b));\n"
                + "endmodule\n"
                + "module adder #(parameter N = 8) (input [N-1:0] a, b, input cin, output [N-1:0] sum, output cout);\n"
                + "  wire [N:0] c;\n"
                + "  assign c[0] = cin;\n"
                + "  genvar i;\n"
                + "  generate\n"
                + "    for (i = 0; i < N; i = i + 1) begin : stage\n"
                + "      full_adder fa(.a(a[i]), .b(b[i]), .ci(c[i]), .s(sum[i]), .co(c[i+1]));\n"
                + "    end\n"
                + "  endgenerate\n"
                + "  assign cout = c[N];\n"
                + "endmodule\n"
                + "module top(input [3:0] x, input [3:0] y, output [3:0] s4, output c4, output [5:0] s6);\n"
                + "  adder #(.N(4)) a4 (.a(x), .b(y), .cin(1'b0), .sum(s4), .cout(c4));\n"
                + "  adder #(6) a6 ({2'b00, x}, {2'b01, y}, 1'b1, s6, );\n"
                + "endmodule\n";
        HdlTestSim s = sim("top", code, "x:4,y:4", "s4:4,c4,s6:6");
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++) {
                s.set("x", x).set("y", y).step();
                assertEquals((x + y) & 15, s.get("s4"));
                assertEquals((x + y) >> 4, s.get("c4"));
                assertEquals((x + y + 16 + 1) & 63, s.get("s6"));
            }
        // the default value of the parameter
        s = sim("adder", code, "a:8,b:8,cin", "sum:8,cout");
        s.set("a", 200).set("b", 100).set("cin", 1).step();
        assertEquals(301 & 255, s.get("sum"));
        assertEquals(1, s.get("cout"));
    }

    public void testBlockingAndNonBlocking() throws Exception {
        String code = "module swap(input clk, input load, input [3:0] ia, input [3:0] ib,\n"
                + "            output reg [3:0] a, output reg [3:0] b, output reg [3:0] c, output reg [3:0] d);\n"
                + "  always @(posedge clk)\n"
                + "    if (load) begin a <= ia; b <= ib; end\n"
                + "    else begin a <= b; b <= a; end      // non-blocking: swap\n"
                + "  always @(posedge clk)\n"
                + "    if (load) begin c = ia; d = ib; end\n"
                + "    else begin c = d; d = c; end        // blocking: both get the value of d\n"
                + "endmodule\n";
        HdlTestSim s = sim("swap", code, "clk,load,ia:4,ib:4", "a:4,b:4,c:4,d:4");
        s.set("load", 1).set("ia", 3).set("ib", 9).step().clock("clk");
        assertEquals(3, s.get("a"));
        assertEquals(9, s.get("b"));
        assertEquals(3, s.get("c"));
        assertEquals(9, s.get("d"));
        s.set("load", 0).step().clock("clk");
        assertEquals(9, s.get("a"));
        assertEquals(3, s.get("b"));
        assertEquals(9, s.get("c"));
        assertEquals(9, s.get("d"));
        s.clock("clk");
        assertEquals(3, s.get("a"));
        assertEquals(9, s.get("b"));
    }

    public void testSignedArithmeticAndLoops() throws Exception {
        String code = "module arith(input signed [7:0] a, input signed [7:0] b, output signed [8:0] sum,\n"
                + "             output gt, output [7:0] sra, output [15:0] prod, output [3:0] ones, output [7:0] rev,\n"
                + "             output [7:0] mixed);\n"
                + "  localparam W = $clog2(256);\n"
                + "  reg [3:0] cnt;\n"
                + "  reg [W-1:0] r;\n"
                + "  integer i;\n"
                + "  assign sum = a + b;\n"
                + "  assign gt = a > b;\n"
                + "  assign sra = a >>> 2;\n"
                + "  assign prod = $unsigned(a) * $unsigned(b);\n"
                + "  assign mixed = a + 8'd1;    // unsigned because one operand is unsigned\n"
                + "  always @* begin\n"
                + "    cnt = 0;\n"
                + "    for (i = 0; i < 8; i = i + 1) begin\n"
                + "      cnt = cnt + a[i];\n"
                + "      r[7 - i] = a[i];\n"
                + "    end\n"
                + "  end\n"
                + "  assign ones = cnt;\n"
                + "  assign rev = r;\n"
                + "endmodule\n";
        HdlTestSim s = sim("arith", code, "a:8,b:8", "sum:9,gt,sra:8,prod:16,ones:4,rev:8,mixed:8");
        int[] values = {-128, -100, -7, -1, 0, 1, 5, 99, 127};
        for (int a : values)
            for (int b : values) {
                s.set("a", a & 255).set("b", b & 255).step();
                assertEquals((a + b) & 511, s.get("sum"));
                assertEquals(a > b ? 1 : 0, s.get("gt"));
                assertEquals((a >> 2) & 255, s.get("sra"));
                assertEquals(((a & 255) * (b & 255)) & 0xFFFF, s.get("prod"));
                assertEquals(Integer.bitCount(a & 255), s.get("ones"));
                assertEquals(Integer.reverse(a & 255) >>> 24, s.get("rev"));
                assertEquals((a + 1) & 255, s.get("mixed"));
            }
    }

    public void testUnsupported() {
        String code = "module top(input a, output y);\n"
                + "  missing u1(.i(a), .o(y));\n"
                + "endmodule\n";
        String msg = VerilogInterpreter.check("top", code);
        assertNotNull(msg);
        assertTrue(msg, msg.contains("missing"));

        msg = VerilogInterpreter.check("t", "module t(input a, output y);\n  task foo; endtask\nendmodule\n");
        assertNotNull(msg);
        assertTrue(msg, msg.contains("task"));

        msg = VerilogInterpreter.check("t", "module t(input a, output y);\n  assign y = b;\nendmodule\n");
        assertNotNull(msg);
        assertTrue(msg, msg.contains("'b'"));
    }
}
