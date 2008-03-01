/*
 * Parser.java
 *
 * Parses a MIPS64 source code and fills the symbol table and the memory.
 *
 * (c) 2008 Andrea Spadaccini
 *
 * This file is part of the EduMIPS64 project, and is released under the GNU
 * General Public License.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edumips64.core.parser;

import edumips64.utils.IrregularStringOfBitsException;
import edumips64.core.*;
import edumips64.core.is.Instruction;
import edumips64.core.parser.tokens.*;
import java.util.*;

public class Parser {
    // Association between directives and parsing algorithms (Strategy design
    // pattern)
    protected HashMap<String, ParsingAlgorithm> algorithms;

    // Stupid inner class, because we are using a stupid programming language
    // And no, I'm not going to write getters and setters.
    protected class InstructionData {
        public InstructionData(Instruction instr, List<Token> params, int address, Token token) {
            this.instr = instr;
            this.params = params;
            this.address = address;
            this.token = token;
        }
        public Instruction instr;
        public Token token;
        public int address;
        public List<Token> params;
    }

    // List of instructions with parameters
    protected List<InstructionData> instructions;

    protected static Parser instance;
    protected Scanner scanner;
    protected ParsingAlgorithm default_alg;
    protected SymbolTable symbols;
    protected Memory memory;
    /* --------------------
     * Public methods
     * --------------------
     */
    public void parse(Scanner s) {
        System.out.println("Starting the parser subsystem");
        scanner = s;
        default_alg.parse(s);

        System.out.println("Will now pack the instructions");
        for(InstructionData i : instructions) {
            System.out.println("Processing " + i.instr.getFullName());
            for(Token t : i.params) {
                System.out.println("Adding " + t + " to parameters list");
                try {
                    t.addToParametersList(i.instr);
                }
                catch(ParameterException e) {
                    addError(t, e.getMessage());
                }
            }

            try {
                i.instr.pack();
                memory.addInstruction(i.instr, i.address);
            }
            catch (SymbolTableOverflowException e) {
                addError(i.token, "Address out of range");
            }
            catch (IrregularStringOfBitsException e) {
                addError(i.token, "Unexpected error");
            }
        }
    }

    // Singleton design pattern
    public static Parser getInstance() {
        if(instance == null)
            instance = new Parser();
        return instance;
    };
    /* ----------------------------
     * Package-wide visible methods
     * used by parsing algorithms
     * ----------------------------
     */
    boolean hasAlgorithm(String directive) {
        return algorithms.containsKey(directive);
    }

    void switchParsingAlgorithm(String directive) { 
        System.out.println("Switching parser due to directive " + directive);
        algorithms.get(directive).parse(scanner);
    }

    // TODO: right now the addError method prints the error.
    // It will use ParserMultiException to report errors to user.
    void addError(Token t, String error) {
        System.out.println("************* " + error + ": " + t);
    }

    void addInstruction(Instruction instr, int address, List<Token> params, String label, Token instrToken) {
        //System.out.println("Adding " + instr + " to SymbolTable, label " + label + ", address " + address);
        if(label != null) {
            try {
                symbols.setInstructionLabel(address, label);
                System.out.println("ADDED LABEL " + label);
            }
            catch (SameLabelsException e) {
                addError(instrToken, "Duplicate label");
            }
        }
        // For later parameters processing
        instructions.add(new InstructionData(instr, params, address, instrToken));
    }


    void addMemoryAddressToSymbolTable(int address, Token label) throws MemoryElementNotFoundException{
        System.out.println("Adding " + label.getBuffer() + " to SymbolTable, address " + address);
        try {
            symbols.setCellLabel(address, label.getBuffer());
        }
        catch (SameLabelsException e) {
            addError(label, "Duplicate label");
        }
    }


    static boolean isInstruction(Token t) {
        // TODO: should we improve it?
        return Instruction.buildInstruction(t.getBuffer()) != null;
    }


    /* -----------------
     * Protected methods
     * -----------------
     */
    protected Parser() {
        algorithms = new HashMap<String, ParsingAlgorithm>();
        default_alg = new NullParsingAlgorithm(this);
        CodeParsingAlgorithm code_pa = new CodeParsingAlgorithm(this);
        DataParsingAlgorithm data_pa = new DataParsingAlgorithm(this);
        symbols = SymbolTable.getInstance();
        memory = Memory.getInstance();
        instructions = new LinkedList<InstructionData>();

        // Association of parsing algorithms with directives
        registerAlgorithm(".DATA", data_pa);
        registerAlgorithm(".CODE", code_pa);
        registerAlgorithm(".TEXT", code_pa);

    }

    protected void registerAlgorithm(String directive, ParsingAlgorithm p) {
        System.out.println("Registering a parser for directive " + directive + ", " + p.toString());
        algorithms.put(directive, p);
    }

}
