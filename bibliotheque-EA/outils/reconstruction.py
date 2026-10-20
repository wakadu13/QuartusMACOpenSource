#!/usr/bin/env python3
"""
Generates the blocks of the EA library which were missing and had to be rebuilt.
See ../RECONSTRUCTIONS.md for the reasons behind every choice.

Usage (from the folder bibliotheque-EA):  python3 outils/reconstruction.py

The schematics are written in the Quartus block design format (bdf, version 1.3).
The connections are made by named connectors ("labels"): every port of a symbol gets a short
wire carrying the name of its net, as is common in Quartus schematics. The primitive symbols
(DFF, AND2, NOT) are copied from the schematics of the library.
"""
import re
import os

LIB = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

HEADER = '''/*
RECONSTRUCTION - this file is NOT the original %s of the EA library.
It was rebuilt on 2026-10-20 by outils/reconstruction.py from the symbol "%s" found in %s.
%s
See RECONSTRUCTIONS.md for details.
*/
(header "graphic" (version "1.3"))
'''

IN_LINES = '''	(drawing
		(line (pt 92 12)(pt 117 12)(line_width 1))
		(line (pt 92 4)(pt 117 4)(line_width 1))
		(line (pt 121 8)(pt 168 8)(line_width 1))
		(line (pt 92 12)(pt 92 4)(line_width 1))
		(line (pt 117 4)(pt 121 8)(line_width 1))
		(line (pt 117 12)(pt 121 8)(line_width 1))
	)'''
OUT_LINES = '''	(drawing
		(line (pt 0 8)(pt 52 8)(line_width 1))
		(line (pt 52 4)(pt 78 4)(line_width 1))
		(line (pt 52 12)(pt 78 12)(line_width 1))
		(line (pt 52 12)(pt 52 4)(line_width 1))
		(line (pt 78 4)(pt 82 8)(line_width 1))
		(line (pt 82 8)(pt 78 12)(line_width 1))
	)'''


def read(name):
    with open(os.path.join(LIB, name), encoding='latin-1') as f:
        return f.read()


def write(name, text):
    with open(os.path.join(LIB, name), 'w', encoding='latin-1') as f:
        f.write(text)


def esc(s):
    return s.replace('"', "'")


class Schematic:
    def __init__(self):
        self.parts = []
        self.inst = 1

    def input_pin(self, name, x, y):
        """input pin, its connection point is (x, y)"""
        w = 5 + 6 * len(name)
        self.parts.append(f'(pin\n\t(input)\n\t(rect {x - 168} {y - 8} {x} {y + 8})\n'
                          f'\t(text "INPUT" (rect 125 0 153 10)(font "Arial" (font_size 6)))\n'
                          f'\t(text "{esc(name)}" (rect 5 0 {w} 12)(font "Arial" ))\n\t(pt 168 8)\n{IN_LINES}\n'
                          f'\t(text "VCC" (rect 128 7 148 17)(font "Arial" (font_size 6)))\n)\n')

    def output_pin(self, name, x, y):
        """output pin, its connection point is (x, y)"""
        w = 90 + 6 * len(name)
        self.parts.append(f'(pin\n\t(output)\n\t(rect {x} {y - 8} {x + 176} {y + 8})\n'
                          f'\t(text "OUTPUT" (rect 1 0 39 10)(font "Arial" (font_size 6)))\n'
                          f'\t(text "{esc(name)}" (rect 90 0 {w} 12)(font "Arial" ))\n\t(pt 0 8)\n{OUT_LINES}\n)\n')

    def wire(self, x1, y1, x2, y2, label=None, bus=False):
        text = ''
        if label:
            lx = min(x1, x2) + 2
            ly = min(y1, y2)
            text = f'\t(text "{esc(label)}" (rect {lx} {ly - 12} {lx + 6 * len(label)} {ly})(font "Arial" ))\n'
        b = '\t(bus)\n' if bus else ''
        self.parts.append(f'(connector\n{text}\t(pt {x1} {y1})\n\t(pt {x2} {y2})\n{b})\n')

    def symbol(self, template, x, y, nets, params=None):
        """
        Places a symbol; template is the text of a symbol, its rect is replaced.
        nets maps a port name to the name of the net, a short labeled wire is drawn at the port.
        """
        m = re.search(r'\(rect (-?\d+) (-?\d+) (-?\d+) (-?\d+)\)', template)
        w = int(m.group(3)) - int(m.group(1))
        h = int(m.group(4)) - int(m.group(2))
        t = template.replace(m.group(0), f'(rect {x} {y} {x + w} {y + h})', 1)
        # the instance name is the second text
        texts = list(re.finditer(r'\(text "([^"]*)"', t))
        inst = f'inst{self.inst}'
        self.inst += 1
        t = t[:texts[1].start()] + f'(text "{inst}"' + t[texts[1].end():]
        if params:
            p = ''.join(f'\t(parameter "{k}" "{v}" "" )\n' for k, v in params.items())
            t = t.replace('\t(drawing', p + '\t(drawing', 1)
        self.parts.append(t if t.endswith('\n') else t + '\n')
        for port in re.finditer(r'\(port\s*\(pt (\d+) (\d+)\)\s*\((\w+)\)\s*\(text "([^"]*)"', t):
            name = port.group(4)
            if name not in nets:
                continue
            px = x + int(port.group(1))
            py = y + int(port.group(2))
            net = nets[name]
            bus = '[' in net and '..' in net
            if px <= x:
                self.wire(px - 40, py, px, py, net, bus)
            elif px >= x + w:
                self.wire(px, py, px + 40, py, net, bus)
            elif py <= y:
                self.wire(px, py - 24, px, py, net, bus)
            else:
                self.wire(px, py, px, py + 24, net, bus)

    def text(self, header):
        return header + ''.join(self.parts)


def template(file, type_name):
    """the text of the first symbol of the given type in a schematic of the library"""
    s = read(file)
    for m in re.finditer(r'^\(symbol\n(.*?)^\)', s, re.S | re.M):
        if re.findall(r'text "([^"]*)"', m.group(1))[0] == type_name:
            return m.group(0)
    raise Exception(f'{type_name} not found in {file}')


def box_symbol(type_name, inputs, outputs, width=160):
    """a rectangular symbol like the symbols created by Quartus for blocks and megafunctions"""
    n = max(len(inputs), len(outputs))
    h = 32 + 16 * n + 16
    s = f'(symbol\n\t(rect 0 0 {width} {h})\n'
    s += f'\t(text "{type_name}" (rect 5 0 {5 + 7 * len(type_name)} 12)(font "Arial" (font_size 8)))\n'
    s += f'\t(text "inst" (rect 8 {h - 16} 25 {h - 4})(font "Arial" ))\n'
    for i, name in enumerate(inputs):
        y = 32 + 16 * i
        bus = 3 if '[' in name else 1
        s += (f'\t(port\n\t\t(pt 0 {y})\n\t\t(input)\n'
              f'\t\t(text "{name}" (rect 0 0 {6 * len(name)} 12)(font "Arial" (font_size 8)))\n'
              f'\t\t(text "{name}" (rect 21 {y - 5} {21 + 6 * len(name)} {y + 7})(font "Arial" (font_size 8)))\n'
              f'\t\t(line (pt 0 {y})(pt 16 {y})(line_width {bus}))\n\t)\n')
    for i, name in enumerate(outputs):
        y = 32 + 16 * i
        bus = 3 if '[' in name else 1
        s += (f'\t(port\n\t\t(pt {width} {y})\n\t\t(output)\n'
              f'\t\t(text "{name}" (rect 0 0 {6 * len(name)} 12)(font "Arial" (font_size 8)))\n'
              f'\t\t(text "{name}" (rect {width - 21 - 6 * len(name)} {y - 5} {width - 21} {y + 7})(font "Arial" (font_size 8)))\n'
              f'\t\t(line (pt {width} {y})(pt {width - 16} {y})(line_width {bus}))\n\t)\n')
    s += f'\t(drawing\n\t\t(rectangle (rect 16 16 {width - 16} {h - 16})(line_width 1))\n\t)\n)\n'
    return s


def bsf_from_bdf_symbol(file, type_name, out_name, comment):
    """creates a symbol file (bsf) from the symbol drawn in a schematic"""
    sym = template(file, type_name)
    m = re.search(r'\(rect (-?\d+) (-?\d+) (-?\d+) (-?\d+)\)', sym)
    w = int(m.group(3)) - int(m.group(1))
    h = int(m.group(4)) - int(m.group(2))
    sym = sym.replace(m.group(0), f'(rect 0 0 {w} {h})', 1)
    write(out_name, f'/*\n{comment}\n*/\n(header "symbol" (version "1.1"))\n' + sym + '\n')


# ---------------------------------------------------------------------------------------------
# compteurMod4: like compteur3b, but counting modulo 4 (used by DriverAffich4D)
# ---------------------------------------------------------------------------------------------
def compteur_mod4():
    s = read('compteur3b.tdf')
    s = s.replace('SUBDESIGN compteur3b', 'SUBDESIGN compteurMod4')
    s = s.replace('\t\t\tLPM_DIRECTION = "UP",', '\t\t\tLPM_DIRECTION = "UP",\n\t\t\tLPM_MODULUS = 4,')
    s = re.sub(r'(?s)^.*?INCLUDE', '''-- RECONSTRUCTION - this file is NOT part of the original EA library.
-- Copy of compteur3b.tdf counting modulo 4 (LPM_MODULUS = 4): q = 0, 1, 2, 3, 0, ...
-- Created on 2026-10-20 by outils/reconstruction.py for DriverAffich4D. See RECONSTRUCTIONS.md.

INCLUDE''', s, count=1)
    assert 'LPM_MODULUS = 4' in s
    write('compteurMod4.tdf', s)


# ---------------------------------------------------------------------------------------------
# DriverAffich4D: DriverAffich8D with a digit counter modulo 4
# ---------------------------------------------------------------------------------------------
def driver_affich4d():
    s = read('DriverAffich8D.bdf')
    n = s.count('(text "compteur3b"')
    assert n == 1
    s = s.replace('(text "compteur3b"', '(text "compteurMod4"')
    s = s.replace('(header "graphic" (version "1.3"))', HEADER % (
        'DriverAffich4D', 'DriverAffich4D', 'Afficheur8D.bdf',
        'Copy of DriverAffich8D.bdf where the digit counter compteur3b is replaced by compteurMod4:\n'
        'only the digits 0 to 3 are sent.'), 1)
    write('DriverAffich4D.bdf', s)
    bsf_from_bdf_symbol('Afficheur8D.bdf', 'DriverAffich4D', 'DriverAffich4D.bsf',
                        'RECONSTRUCTION - symbol copied from the instance in Afficheur8D.bdf')


# ---------------------------------------------------------------------------------------------
# comptdecompt12b: 3 digit BCD up/down counter, 3 LPM_COUNTER modulo 10 in cascade
# ---------------------------------------------------------------------------------------------
def comptdecompt12b():
    sc = Schematic()
    counter = box_symbol('LPM_COUNTER', ['updown', 'clock', 'cnt_en', 'cin'], ['q[3..0]', 'cout'], 176)
    params = {'LPM_WIDTH': '4', 'LPM_MODULUS': '10', 'LPM_PORT_UPDOWN': 'PORT_USED', 'LPM_TYPE': 'LPM_COUNTER'}
    sc.input_pin('Up/Down', 160, 40)
    sc.wire(160, 40, 200, 40, 'Up/Down')
    sc.input_pin('clk', 160, 72)
    sc.wire(160, 72, 200, 72, 'clk')
    sc.output_pin('s[11..0]', 880, 40)
    sc.wire(840, 40, 880, 40, 's[11..0]', True)
    # units: always counting
    sc.symbol(counter, 400, 0, {'updown': 'Up/Down', 'clock': 'clk', 'q[3..0]': 's[3..0]', 'cout': 'r0'}, params)
    # tens: count when the units overflow (9 -> 0 upwards, 0 -> 9 downwards)
    sc.symbol(counter, 400, 160, {'updown': 'Up/Down', 'clock': 'clk', 'cnt_en': 'r0', 'cin': 'r0',
                                  'q[3..0]': 's[7..4]', 'cout': 'r1'}, params)
    # hundreds: count when the units and the tens overflow
    sc.symbol(counter, 400, 320, {'updown': 'Up/Down', 'clock': 'clk', 'cnt_en': 'r1', 'cin': 'r1',
                                  'q[3..0]': 's[11..8]'}, params)
    write('comptdecompt12b.bdf', sc.text(HEADER % (
        'comptdecompt12b', 'comptdecompt12b', 'Afficheur8D.bdf',
        'Three digit decimal (BCD) up/down counter: s[3..0] units, s[7..4] tens, s[11..8] hundreds.\n'
        'Counts on the rising edge of clk, up if Up/Down = 1, down if Up/Down = 0, 000..999.')))
    bsf_from_bdf_symbol('Afficheur8D.bdf', 'comptdecompt12b', 'comptdecompt12b.bsf',
                        'RECONSTRUCTION - symbol copied from the instance in Afficheur8D.bdf')


# ---------------------------------------------------------------------------------------------
# affich3digit: three BCD to seven segment decoders (DEC_7SEG of the library)
# ---------------------------------------------------------------------------------------------
def affich3digit():
    sc = Schematic()
    segs = ['segment_a', 'segment_b', 'segment_c', 'segment_d', 'segment_e', 'segment_f', 'segment_g']
    dec = box_symbol('dec_7seg', ['hex_digit[3..0]'], segs, 192)
    sc.input_pin('E[11..0]', 160, 40)
    sc.wire(160, 40, 200, 40, 'E[11..0]', True)
    y = 0
    for name, bits in (('c', 'E[11..8]'), ('d', 'E[7..4]'), ('u', 'E[3..0]')):
        nets = {'hex_digit[3..0]': bits}
        for i, sg in enumerate(segs):
            nets[sg] = f'{name}[{i + 1}]'
        sc.symbol(dec, 400, y, nets)
        sc.output_pin(f'{name}[1..7]', 760, y + 40)
        sc.wire(720, y + 40, 760, y + 40, f'{name}[1..7]', True)
        y += 176
    write('affich3digit.bdf', sc.text(HEADER % (
        'affich3digit', 'affich3digit', 'Afficheur8D.bdf',
        'Three decoders DEC_7SEG (library): E[11..8] -> c (hundreds), E[7..4] -> d (tens), E[3..0] -> u (units).\n'
        'Outputs [1..7] = segments a..g, active low (0 = segment lit) as on the DE0 board.')))
    bsf_from_bdf_symbol('Afficheur8D.bdf', 'affich3digit', 'affich3digit.bsf',
                        'RECONSTRUCTION - symbol copied from the instance in Afficheur8D.bdf')


# ---------------------------------------------------------------------------------------------
# InterfaceBouton: interface of an incremental rotary encoder (channels E1 and E2)
# ---------------------------------------------------------------------------------------------
def interface_bouton():
    sc = Schematic()
    dff = template('detect1vers0.bdf', 'DFF')
    and2 = template('detect1vers0.bdf', 'AND2')
    inv = template('detect1vers0.bdf', 'NOT')
    sc.input_pin('E1', 160, 40)
    sc.wire(160, 40, 200, 40, 'E1')
    sc.input_pin('E2', 160, 88)
    sc.wire(160, 88, 200, 88, 'E2')
    sc.input_pin('H10k', 160, 136)
    sc.wire(160, 136, 200, 136, 'H10k')
    # E1 and E2 are sampled by the clock (synchronization, filtering of the bounces)
    sc.symbol(dff, 400, 0, {'D': 'E1', 'CLK': 'H10k', 'Q': 'a1'})
    sc.symbol(dff, 560, 0, {'D': 'a1', 'CLK': 'H10k', 'Q': 'a2'})
    sc.symbol(dff, 400, 160, {'D': 'E2', 'CLK': 'H10k', 'Q': 'b1'})
    # Pulse: one clock period after each rising edge of E1
    sc.symbol(inv, 720, 0, {'IN': 'a2', 'OUT': 'na2'})
    sc.symbol(and2, 880, 0, {'IN1': 'a1', 'IN2': 'na2', 'OUT': 'Pulse'})
    sc.output_pin('Pulse', 1040, 40)
    sc.wire(1000, 40, 1040, 40, 'Pulse')
    # Sens: the state of E2 at the edge of E1 gives the direction of rotation
    sc.symbol(inv, 720, 160, {'IN': 'b1', 'OUT': 'Sens'})
    sc.output_pin('Sens', 1040, 200)
    sc.wire(1000, 200, 1040, 200, 'Sens')
    write('InterfaceBouton.bdf', sc.text(HEADER % (
        'InterfaceBouton', 'InterfaceBouton', 'Afficheur8D.bdf',
        'Interface of an incremental rotary encoder: E1 and E2 are the channels A and B, sampled by H10k.\n'
        'Pulse = 1 during one period of H10k after each rising edge of E1, Sens = NOT E2 at that time.')))
    bsf_from_bdf_symbol('Afficheur8D.bdf', 'InterfaceBouton', 'InterfaceBouton.bsf',
                        'RECONSTRUCTION - symbol copied from the instance in Afficheur8D.bdf')


# ---------------------------------------------------------------------------------------------
# XOR8, XOR4: exclusive OR of 8 and 4 inputs, a tree of 2-input XOR primitives
# ---------------------------------------------------------------------------------------------
XOR_SYMBOL = '''(symbol
	(rect {x} {y} {x2} {y2})
	(text "XOR" (rect 1 0 21 10)(font "Arial" (font_size 6)))
	(text "{inst}" (rect 3 37 26 49)(font "Arial" ))
	(port
		(pt 0 16)
		(input)
		(text "IN1" (rect 2 7 23 19)(font "Courier New" (bold))(invisible))
		(text "IN1" (rect 2 7 23 19)(font "Courier New" (bold))(invisible))
		(line (pt 0 16)(pt 15 16)(line_width 1))
	)
	(port
		(pt 0 32)
		(input)
		(text "IN2" (rect 2 23 23 35)(font "Courier New" (bold))(invisible))
		(text "IN2" (rect 2 23 23 35)(font "Courier New" (bold))(invisible))
		(line (pt 0 32)(pt 15 32)(line_width 1))
	)
	(port
		(pt 64 24)
		(output)
		(text "OUT" (rect 48 15 69 27)(font "Courier New" (bold))(invisible))
		(text "OUT" (rect 48 15 69 27)(font "Courier New" (bold))(invisible))
		(line (pt 48 24)(pt 64 24)(line_width 1))
	)
	(drawing
		(line (pt 14 36)(pt 25 36)(line_width 1))
		(line (pt 14 13)(pt 25 13)(line_width 1))
		(arc (pt 7 36)(pt 7 13)(rect -14 8 13 41)(line_width 1))
		(arc (pt 14 36)(pt 14 13)(rect -7 8 20 41)(line_width 1))
		(arc (pt 49 25)(pt 25 13)(rect -6 13 50 69)(line_width 1))
		(arc (pt 25 36)(pt 49 24)(rect -6 -20 50 36)(line_width 1))
	)
)
'''


def xor_tree(n, name):
    """the inputs IN1..INn feed a tree of XOR gates; the gates of a level are connected by wires"""
    def inpin(pin, x, y):
        return (f'(pin\n\t(input)\n\t(rect {x - 168} {y - 8} {x} {y + 8})\n'
                f'\t(text "INPUT" (rect 125 0 153 10)(font "Arial" (font_size 6)))\n'
                f'\t(text "{pin}" (rect 5 0 30 12)(font "Arial" ))\n\t(pt 168 8)\n{IN_LINES}\n'
                f'\t(text "VCC" (rect 128 7 148 17)(font "Arial" (font_size 6)))\n)\n')

    def conn(a, b):
        return f'(connector\n\t(pt {a[0]} {a[1]})\n\t(pt {b[0]} {b[1]})\n)\n'

    out = ('/*\nRECONSTRUCTION - this file is NOT the original %s of the EA library.\n'
           'It was rebuilt on 2026-10-20 from the symbol "%s" found in GenerateurArinc.bdf\n'
           '(inputs IN1..IN%d, output OUT): an exclusive OR of %d inputs (parity), built as a tree\n'
           'of 2-input XOR primitives. See RECONSTRUCTIONS.md for details.\n*/\n'
           '(header "graphic" (version "1.3"))\n') % (name, name, n, n)
    x0, dx, inst = 240, 128, 1
    gates = []
    for i in range(n // 2):
        x, y = x0, i * 80
        out += XOR_SYMBOL.format(x=x, y=y, x2=x + 64, y2=y + 48, inst='inst%d' % inst)
        inst += 1
        out += inpin('IN%d' % (2 * i + 1), x, y + 16) + inpin('IN%d' % (2 * i + 2), x, y + 32)
        gates.append((x, y))
    level = 1
    while len(gates) > 1:
        nxt = []
        for j in range(len(gates) // 2):
            (ax, ay), (bx, by) = gates[2 * j], gates[2 * j + 1]
            x = x0 + level * dx
            y = (ay + by) // 2
            out += XOR_SYMBOL.format(x=x, y=y, x2=x + 64, y2=y + 48, inst='inst%d' % inst)
            inst += 1
            # the upper source is connected to IN1, the lower one to IN2, using two different columns
            sa = (ax + 64, ay + 24)
            sb = (bx + 64, by + 24)
            m1 = ax + 64 + 16
            m2 = ax + 64 + 32
            out += conn(sa, (m1, sa[1])) + conn((m1, sa[1]), (m1, y + 16)) + conn((m1, y + 16), (x, y + 16))
            out += conn(sb, (m2, sb[1])) + conn((m2, sb[1]), (m2, y + 32)) + conn((m2, y + 32), (x, y + 32))
            nxt.append((x, y))
        gates = nxt
        level += 1
    x, y = gates[0]
    out += (f'(pin\n\t(output)\n\t(rect {x + 64} {y + 16} {x + 240} {y + 32})\n'
            f'\t(text "OUTPUT" (rect 1 0 39 10)(font "Arial" (font_size 6)))\n'
            f'\t(text "OUT" (rect 90 0 113 12)(font "Arial" ))\n\t(pt 0 8)\n{OUT_LINES}\n)\n')
    with open(os.path.join(LIB, name + '.bdf'), 'w') as f:
        f.write(out)


if __name__ == '__main__':
    xor_tree(8, 'XOR8')
    xor_tree(4, 'XOR4')
    compteur_mod4()
    driver_affich4d()
    comptdecompt12b()
    affich3digit()
    interface_bouton()
    print('done')
