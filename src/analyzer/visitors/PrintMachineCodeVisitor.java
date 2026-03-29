package analyzer.visitors;

import analyzer.ast.*;

import java.io.PrintWriter;
import java.util.*;

public class PrintMachineCodeVisitor implements ParserVisitor {
    private final PrintWriter m_writer;

    private int MAX_REGISTERS_COUNT = 256;

    private final ArrayList<String> RETURNS = new ArrayList<>();
    private final ArrayList<MachineCodeLine> CODE = new ArrayList<>();

    private final ArrayList<String> MODIFIED = new ArrayList<>();
    private final ArrayList<String> REGISTERS = new ArrayList<>();

    private final HashMap<String, String> OPERATIONS = new HashMap<>();

    public PrintMachineCodeVisitor(PrintWriter writer) {
        m_writer = writer;

        OPERATIONS.put("+", "ADD");
        OPERATIONS.put("-", "MIN");
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

        CODE.add(new MachineCodeLine("-", assignTo, "#", right));
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
        return "#"+node.getValue();
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
        if(CODE.isEmpty()) return;

        for (int i = CODE.size() - 1; i >=0 ; i--) {
            MachineCodeLine current = CODE.get(i);

            if (i < CODE.size() - 1) {
                current.Next_OUT = CODE.get(i + 1).Next_IN;
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
        int registerNumber = 0;
        int index1 = -1;

        if (variable.startsWith("#")) {
            return variable;
        }

        if(loadIfNotFound ){
            MODIFIED.add(variable);
        }

        int index = REGISTERS.indexOf(variable);
        if(REGISTERS.contains(variable)){
            return "R" + index;
        }

        if (REGISTERS.size() < MAX_REGISTERS_COUNT) {
            REGISTERS.add(variable);

            if (loadIfNotFound) {
                m_writer.println("LD " + "R" + (REGISTERS.size() - 1) + ", " + variable);
            }

            return "R" + (REGISTERS.size() - 1);

        }
        if (REGISTERS.size() == MAX_REGISTERS_COUNT){

            for(int i = 0; i<this.REGISTERS.size(); i++){

                if(!(next.nextUse.containsKey(this.REGISTERS.get(i))) && this.MODIFIED.contains(this.REGISTERS.get(i)) && RETURNS.contains(this.REGISTERS.get(i))) {
                    m_writer.println("ST " + this.REGISTERS.get(i) + ", " + "R" + i);

                    this.MODIFIED.remove(this.REGISTERS.get(i));
                    m_writer.println("LD " + "R" + i + ", " + variable);
                    this.REGISTERS.set(i, variable);
                    return "R" + i;
                }

            }

            for(int i = 0; i < this.REGISTERS.size(); i++) {
                if (!life.contains(this.REGISTERS.get(i))) {
                    this.REGISTERS.set(i, variable);
                    if (!loadIfNotFound ) {
                        m_writer.println("LD " + "R" + i + ", " + variable);
                    }
                    return "R" + i;
                }
            }


            for(int i = 0; i < this.REGISTERS.size(); i++) {

                if (next.nextUse.containsKey(this.REGISTERS.get(i))) {
                    if (index1 < Collections.min(next.nextUse.get(this.REGISTERS.get(i)))) {
                        registerNumber = i;
                        index1 = Collections.min(next.nextUse.get(this.REGISTERS.get(i)));
                    }
                } else {
                    if (this.MODIFIED.contains(this.REGISTERS.get(i))) {
                        this.MODIFIED.remove(this.REGISTERS.get(i));
                        m_writer.println("ST " + this.REGISTERS.get(i) + ", " + "R" + i);
                    }
                    this.REGISTERS.set(i, variable);
                    if (!loadIfNotFound) {
                        m_writer.println("LD " + "R" + i + ", " + variable);
                    }
                    return "R" + i;
                }


                if (0 < registerNumber) {
                    this.REGISTERS.set(registerNumber, variable);
                    if (!loadIfNotFound) {
                        m_writer.println("LD " + "R" + registerNumber + ", " + variable);
                    }
                    return "R" + registerNumber;
                }
            }

        }
        return null;
    }

    /**
     * Print the machine code in the output file
     */
    public void printMachineCode() {
        // TODO (ex4): Print the machine code in the output file.
        for (int i = 0; i < CODE.size(); i++) { // print the output
            m_writer.println("// Step " + i);
            String leftReg = chooseRegister(CODE.get(i).LEFT, CODE.get(i).Life_IN, CODE.get(i).Next_IN, true);
            String rightReg = chooseRegister(CODE.get(i).RIGHT, CODE.get(i).Life_IN, CODE.get(i).Next_IN, true);
            String assignReg = chooseRegister(CODE.get(i).ASSIGN, CODE.get(i).Life_OUT, CODE.get(i).Next_OUT, false);
            MODIFIED.add(CODE.get(i).ASSIGN);
            m_writer.println(CODE.get(i).OPERATION + " " + assignReg + ", " + leftReg + ", " + rightReg);
            m_writer.println(CODE.get(i));
        }

        // Handle return
        for (String ret: RETURNS) {
            if (REGISTERS.contains(ret) && MODIFIED.contains(ret)) {
                m_writer.println("ST " + ret + ", R" + REGISTERS.indexOf(ret));
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