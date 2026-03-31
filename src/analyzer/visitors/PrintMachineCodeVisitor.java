package analyzer.visitors;

import analyzer.ast.*;

import java.io.PrintWriter;
import java.util.*;

public class PrintMachineCodeVisitor implements ParserVisitor {
    private PrintWriter m_writer = null;

    private int MAX_REGISTERS_COUNT = 256;

    private final ArrayList<String> RETURNS = new ArrayList<>();
    private final ArrayList<MachineCodeLine> CODE = new ArrayList<>();

    private final ArrayList<String> MODIFIED = new ArrayList<>();
    private final ArrayList<String> REGISTERS = new ArrayList<>();

    private final HashMap<String, String> OPERATIONS = new HashMap<>();

    public PrintMachineCodeVisitor(PrintWriter writer) {
        m_writer = writer;

        OPERATIONS.put("+", "ADD");
        OPERATIONS.put("-", "SUB");
        OPERATIONS.put("*", "MUL");
        OPERATIONS.put("/", "DIV");
    }

    @Override
    public Object visit(SimpleNode node, Object data) {
        return null;
    }

    @Override
    public Object visit(ASTProgram node, Object data) {
        node.childrenAccept(this, null);

        computeLifeVar();
        computeNextUse();

        printMachineCode();

        return null;
    }

    @Override
    public Object visit(ASTNumberRegister node, Object data) {
        MAX_REGISTERS_COUNT = ((ASTIntValue) node.jjtGetChild(0)).getValue();
        return null;
    }

    @Override
    public Object visit(ASTReturnStmt node, Object data) {
        for (int i = 0; i < node.jjtGetNumChildren(); i++) {
            RETURNS.add(((ASTIdentifier) node.jjtGetChild(i)).getValue());
        }
        return null;
    }

    @Override
    public Object visit(ASTBlock node, Object data) {
        node.childrenAccept(this, null);
        return null;
    }

    @Override
    public Object visit(ASTStmt node, Object data) {
        node.childrenAccept(this, null);
        return null;
    }

    @Override
    public Object visit(ASTAssignStmt node, Object data) {
        String assignTo = (String) node.jjtGetChild(0).jjtAccept(this, null);
        String left = (String) node.jjtGetChild(1).jjtAccept(this, null);
        String right = (String) node.jjtGetChild(2).jjtAccept(this, null);
        String op = node.getOp();

        CODE.add(new MachineCodeLine(op, assignTo, left, right));

        return null;
    }

    @Override
    public Object visit(ASTAssignUnaryStmt node, Object data) {
        String assignTo = (String) node.jjtGetChild(0).jjtAccept(this, null);
        String right = (String) node.jjtGetChild(1).jjtAccept(this, null);

        CODE.add(new MachineCodeLine("-", assignTo, "#0", right));
        return null;
    }

    @Override
    public Object visit(ASTAssignDirectStmt node, Object data) {
        String assignTo = (String) node.jjtGetChild(0).jjtAccept(this, null);
        String right = (String) node.jjtGetChild(1).jjtAccept(this, null);


        CODE.add(new MachineCodeLine("+", assignTo, "#0", right));

        return null;
    }

    @Override
    public Object visit(ASTExpr node, Object data) {
        return node.jjtGetChild(0).jjtAccept(this, null);
    }

    @Override
    public Object visit(ASTIntValue node, Object data) {
        return "#" + node.getValue();
    }

    @Override
    public Object visit(ASTIdentifier node, Object data) {
        return node.getValue();
    }

    private void computeLifeVar() {
        for (MachineCodeLine mach : this.CODE) {
            mach.Life_IN = new HashSet<>();
            mach.Life_OUT = new HashSet<>();
        }

        if (!this.CODE.isEmpty()) {
            this.CODE.get(this.CODE.size() - 1).Life_OUT.addAll(RETURNS);
        }

        for (int i = this.CODE.size() - 1; i >= 0; i--) {
            MachineCodeLine current = this.CODE.get(i);

            if (i < this.CODE.size() - 1) {
                current.Life_OUT = new HashSet<>(this.CODE.get(i + 1).Life_IN);
            }

            HashSet<String> newLifeIn = new HashSet<>(current.Life_OUT);
            newLifeIn.removeAll(current.DEF);
            newLifeIn.addAll(current.REF);

            current.Life_IN = newLifeIn;
        }
    }


    private void computeNextUse() {
        if (CODE.isEmpty()) return;

        for (int i = CODE.size() - 1; i >=0 ; i--) {
            MachineCodeLine current = CODE.get(i);

            if (i < CODE.size() - 1) {
                current.Next_OUT = (NextUse) CODE.get(i + 1).Next_IN.clone();
            }

            NextUse in = new NextUse();
            for(String var : current.Next_OUT.nextUse.keySet()) {
                if(!current.DEF.contains(var)) {
                    ArrayList<Integer> uses = current.Next_OUT.get(var);

                    for(int useIndex: uses) {
                        in.add(var, useIndex);
                    }
                }
            }

            for(String ref: current.REF) {
                in.add(ref, i);
            }

            current.Next_IN = in;
        }
    }

    /**
     * This function should generate the LD and ST when needed.
     */
    public String chooseRegister(String variable, HashSet<String> life, NextUse next, boolean loadIfNotFound) {
        if (variable.startsWith("#")) {
            return variable;
        }

        int index = REGISTERS.indexOf(variable);
        if (index >= 0) {
            return "R" + index;
        }

        if (REGISTERS.size() < MAX_REGISTERS_COUNT) {
            REGISTERS.add(variable);
            if (loadIfNotFound) {
                m_writer.println("LD R" + (REGISTERS.size() - 1) + ", " + variable);
            }
            return "R" + (REGISTERS.size() - 1);
        }

        for (int i = 0; i < REGISTERS.size(); i++) {
            String current = REGISTERS.get(i);
            if (!next.nextUse.containsKey(current) && MODIFIED.contains(current) && RETURNS.contains(current)) {
                m_writer.println("ST " + current + ", R" + i);
                MODIFIED.remove(current);
                REGISTERS.set(i, variable);
                if (loadIfNotFound) {
                    m_writer.println("LD R" + i + ", " + variable);
                }
                return "R" + i;
            }
        }

        for (int i = 0; i < REGISTERS.size(); i++) {
            String current = REGISTERS.get(i);
            if (!life.contains(current)) {
                REGISTERS.set(i, variable);
                if (loadIfNotFound) {
                    m_writer.println("LD R" + i + ", " + variable);
                }
                return "R" + i;
            }
        }

        int farthestNextUse = -1;
        int selectedIndex = -1;
        for (int i = 0; i < REGISTERS.size(); i++) {
            String current = REGISTERS.get(i);
            if (!next.nextUse.containsKey(current)) {
                selectedIndex = i;
                break;
            }

            int nextUse = Collections.min(next.get(current));
            if (nextUse > farthestNextUse) {
                farthestNextUse = nextUse;
                selectedIndex = i;
            }
        }

        if (selectedIndex >= 0) {
            String current = REGISTERS.get(selectedIndex);
            if (MODIFIED.contains(current) && life.contains(current)) {
                m_writer.println("ST " + current + ", R" + selectedIndex);
                MODIFIED.remove(current);
            }
            REGISTERS.set(selectedIndex, variable);
            if (loadIfNotFound) {
                m_writer.println("LD R" + selectedIndex + ", " + variable);
            }
            return "R" + selectedIndex;
        }

        return null;
    }

    /**
     * Print the machine code in the output file
     */
    public void printMachineCode() {
        for (int i = 0; i < CODE.size(); i++) {
            m_writer.println("// Step " + i);
            MachineCodeLine line = CODE.get(i);
            String leftReg = chooseRegister(line.LEFT, line.Life_IN, line.Next_IN, true);
            String rightReg = chooseRegister(line.RIGHT, line.Life_IN, line.Next_IN, true);
            String assignReg = chooseRegister(line.ASSIGN, line.Life_OUT, line.Next_OUT, false);

            if (!MODIFIED.contains(line.ASSIGN)) {
                MODIFIED.add(line.ASSIGN);
            }

            if (!(line.OPERATION.equals("ADD") && line.LEFT.equals("#0") && assignReg.equals(rightReg))) {
                m_writer.println(line.OPERATION + " " + assignReg + ", " + leftReg + ", " + rightReg);
            }
            m_writer.println(line);
        }

        for (int i = 0; i < REGISTERS.size(); i++) {
            String current = REGISTERS.get(i);
            if (RETURNS.contains(current) && MODIFIED.contains(current)) {
                m_writer.println("ST " + current + ", R" + i);
                MODIFIED.remove(current);
            }
        }
    }

    /**
     * Order a set in alphabetic order
     *
     * @param set The set to order
     * @return The ordered list
     */
    public List<String> orderedSet(Set<String> set) {
        List<String> list = new ArrayList<>(set);
        Collections.sort(list);
        return list;
    }

    /**
     * A class to store and manage next uses.
     */
    private class NextUse {
        public HashMap<String, ArrayList<Integer>> nextUse = new HashMap<>();

        public NextUse() {}

        public NextUse(HashMap<String, ArrayList<Integer>> nextUse) {
            this.nextUse = nextUse;
        }

        public ArrayList<Integer> get(String s) {
            return nextUse.get(s);
        }

        public void add(String s, int i) {
            if (!nextUse.containsKey(s)) {
                nextUse.put(s, new ArrayList<>());
            }
            nextUse.get(s).add(i);
        }

        public String toString() {
            ArrayList<String> items = new ArrayList<>();
            for (String key : orderedSet(nextUse.keySet())) {
                Collections.sort(nextUse.get(key));
                items.add(String.format("%s:%s", key, nextUse.get(key)));
            }
            return String.join(", ", items);
        }

        @Override
        public Object clone() {
            return new NextUse((HashMap<String, ArrayList<Integer>>) nextUse.clone());
        }
    }

    /**
     * A struct to store the data of a machine code line.
     */
    private class MachineCodeLine {
        String OPERATION;
        String ASSIGN;
        String LEFT;
        String RIGHT;

        public HashSet<String> REF = new HashSet<>();
        public HashSet<String> DEF = new HashSet<>();

        public HashSet<String> Life_IN = new HashSet<>();
        public HashSet<String> Life_OUT = new HashSet<>();

        public NextUse Next_IN = new NextUse();
        public NextUse Next_OUT = new NextUse();

        public MachineCodeLine(String operation, String assign, String left, String right) {
            this.OPERATION = OPERATIONS.get(operation);
            this.ASSIGN = assign;
            this.LEFT = left;
            this.RIGHT = right;

            DEF.add(this.ASSIGN);
            if (this.LEFT.charAt(0) != '#')
                REF.add(this.LEFT);
            if (this.RIGHT.charAt(0) != '#')
                REF.add(this.RIGHT);
        }

        @Override
        public String toString() {
            String buffer = "";
            buffer += String.format("// Life_IN  : %s\n", Life_IN);
            buffer += String.format("// Life_OUT : %s\n", Life_OUT);
            buffer += String.format("// Next_IN  : %s\n", Next_IN);
            buffer += String.format("// Next_OUT : %s\n", Next_OUT);
            return buffer;
        }
    }
}
