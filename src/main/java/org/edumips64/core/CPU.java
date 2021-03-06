/* CPU.java
 *
 * This class models a MIPS CPU with 32 64-bit General Purpose Register.
 * (c) 2006 Andrea Spadaccini, Simona Ullo, Antonella Scandura, Massimo Trubia (FPU modifications)
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

package org.edumips64.core;

import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;

import org.edumips64.core.fpu.*;
import org.edumips64.core.is.AddressErrorException;
import org.edumips64.core.is.BreakException;
import org.edumips64.core.is.HaltException;
import org.edumips64.core.is.InstructionInterface;
import org.edumips64.core.is.JumpException;
import org.edumips64.core.is.RAWException;
import org.edumips64.core.is.TwosComplementSumException;
import org.edumips64.core.is.WAWException;
import org.edumips64.utils.*;

/** This class models a MIPS CPU with 32 64-bit General Purpose Registers.
*  @author Andrea Spadaccini, Simona Ullo, Antonella Scandura, Massimo Trubia (FPU modifications)
*/
public class CPU {
  private Memory mem;
  private Register[] gpr;
  private static final Logger logger = Logger.getLogger(CPU.class.getName());

  /** FPU Elements*/
  private RegisterFP[] fpr;
  private InstructionInterface lastFPInstructionInEx;

  private FCSRRegister FCSR;
  private FPPipeline fpPipe;

  /** Program Counter*/
  private Register pc, old_pc;
  private Register LO, HI;

  /** Pipeline stage*/
  public enum PipeStage {IF, ID, EX, MEM, WB}

  /** CPU status.
   *  READY - the CPU has been initialized but the symbol table hasn't been
   *  already filled by the Parser. This means that you can't call the step()
   *  method, or you'll get a StoppedCPUException.
   *
   *  RUNNING - the CPU is executing a program, you can call the step()
   *  method, and the CPU will fetch additional instructions from the symbol
   *  table
   *
   *  STOPPING - the HALT instruction has entered in the pipeline. This means
   *  that no additional instructions must be fetched but the instructions
   *  that are already in the pipeline must be executed. THe step() method can
   *  be called, but won't fetch any other instruction
   *
   *  HALTED - the HALT instruction has passed the WB stage, and the step()
   *  method can't be executed.
   * */
  public enum CPUStatus {READY, RUNNING, STOPPING, HALTED}
  private CPUStatus status;

  /** CPU pipeline, each stage contains an Instruction object. */
  private Pipeline pipe;

  /** The current stage of the pipeline.*/
  private PipeStage currentPipeStage;

  /** Simulator configuration */
  private ConfigStore config;

  /** Statistics */
  private int cycles, instructions, RAWStalls, WAWStalls, dividerStalls, funcUnitStalls, memoryStalls, exStalls;

  /** BUBBLE */
  private InstructionInterface bubble;

  /** Terminating instructions */
  private static ArrayList<String> terminating = new ArrayList<>(
      Arrays.asList("0000000C",     // SYSCALL 0
                    "04000000"));   // HALT

  public CPU(Memory memory, ConfigStore config, InstructionInterface bubble) {
    this.config = config;
    this.bubble = bubble;

    logger.info("Creating the CPU...");
    cycles = 0;
    status = CPUStatus.READY;
    mem = memory;
    logger.info("Got Memory instance..");

    // Registers initialization
    gpr = new Register[32];
    gpr[0] = new R0();

    for (int i = 1; i < 32; i++) {
      gpr[i] = new Register("R" + i);
    }

    pc = new Register("PC");
    old_pc = new Register("Old PC");
    LO = new Register("LO");
    HI = new Register("HI");

    //Floating point registers initialization
    fpr = new RegisterFP[32];

    for (int i = 0; i < 32; i++) {
      fpr[i] = new RegisterFP();
    }

    FCSR = new FCSRRegister();
    configFPExceptionsAndRM();
    fpPipe = new FPPipeline();
    fpPipe.reset();


    // Pipeline initialization
    pipe = new Pipeline();
    currentPipeStage = PipeStage.IF;
    logger.info("CPU Created.");
  }


// SETTING PROPERTIES ------------------------------------------------------------------
  /** Sets the CPU status.
   *  @param status a CPUStatus value
   */
  public  void setStatus(CPUStatus status) {
    logger.info("Changing CPU status to " + status.name());
    this.status = status;
  }

  /** Sets the flag bits of the FCSR
  * @param tag a string value between  V  Z O U I
  * @param value a binary value
   */
  public void setFCSRFlags(String tag, int value) throws IrregularStringOfBitsException {
    FCSR.setFCSRFlags(tag, value);
  }

  /** Sets the cause bits of the FCSR
  * @param tag a string value between  V  Z O U I
  * @param value a binary value
   */
  public void setFCSRCause(String tag, int value) throws IrregularStringOfBitsException {
    FCSR.setFCSRCause(tag, value);
  }

  /** Sets the selected FCC bit of the FCSR
   * @param cc condition code is an int value in the range [0,7]
   * @param condition the binary value of the relative bit
   */
  public void setFCSRConditionCode(int cc, int condition) throws IrregularStringOfBitsException {
    FCSR.setFCSRConditionCode(cc, condition);
  }

//GETTING PROPERTIES -----------------------------------------------------------------

  /** Gets the CPU status
   *  @return status a CPUStatus value representing the current CPU status
   */
  public CPUStatus getStatus() {
    return status;
  }

  public Register[] getRegisters() {
    return gpr;
  }

  public RegisterFP[] getRegistersFP() {
    return fpr;
  }

  /** This method returns a specific GPR
   * @param index the register number (0-31)
   */
  public Register getRegister(int index) {
    return gpr[index];
  }

  public RegisterFP getRegisterFP(int index) {
    return fpr[index];
  }

  /** Returns true if the specified functional unit is filled by an instruction, false when the contrary happens.
   *  No controls are carried out on the legality of parameters, for mistaken parameters false is returned
   *  @param funcUnit The functional unit to check. Legal values are "ADDER", "MULTIPLIER", "DIVIDER"
   *  @param stage The integer that refers to the stage of the functional unit.
   *      ADDER [1,4], MULTIPLIER [1,7], DIVIDER [any] */
  public boolean isFuncUnitFilled(String funcUnit, int stage) {
    return fpPipe.isFuncUnitFilled(funcUnit, stage);
  }

  /** Returns true if the pipeline is empty. In this case, if CPU is in stopping state
   *  we can halt the pipeline. The sufficient condition in order to return true is that fpPipe doesn't work
   *  and it hadn't issued any instrution now in the MEM stage */
  private boolean isPipelinesEmpty() {
    // WB is not checked because currently this method is called before the
    // instruction in WB is removed from the pipeline.
    return pipe.isEmptyOrBubble(PipeStage.ID) &&
           pipe.isEmptyOrBubble(PipeStage.EX) &&
           pipe.isEmptyOrBubble(PipeStage.MEM) &&
           fpPipe.isEmpty();
  }

  /** Returns the instruction of the specified functional unit , null if it is empty.
   *  No controls are carried out on the legality of parameters, for mistaken parameters null is returned
   *  @param funcUnit The functional unit to check. Legal values are "ADDER", "MULTIPLIER", "DIVIDER"
   *  @param stage The integer that refers to the stage of the functional unit.
   *      ADDER [1,4], MULTIPLIER [1,7], DIVIDER [any] */

  public InstructionInterface getInstructionByFuncUnit(String funcUnit, int stage) {
    return fpPipe.getInstructionByFuncUnit(funcUnit, stage);
  }

  /** Gets the Floating Point Control Status Register*/
  public FCSRRegister getFCSR() {
    return FCSR;
  }

  /** Gets the selected FCC bit of the FCSR
   * @param cc condition code is an int value in the range [0,7]
   */
  public int getFCSRConditionCode(int cc) {
    return FCSR.getFCSRConditionCode(cc);
  }

  /** Gets the current rounding mode readeng the FCSR
   * @return the rounding mode */
  public FCSRRegister.FPRoundingMode getFCSRRoundingMode() {
    return FCSR.getFCSRRoundingMode();
  }

  /** Gets the current computing step of the divider*/
  public int getDividerCounter() {
    return fpPipe.getDividerCounter();
  }

  /** Gets the integer pipeline
   *  @return an HashMap
   */
  public Map<PipeStage, InstructionInterface> getPipeline() {
    // TODO: fix callers to use Pipeline.
    return pipe.getInternalRepresentation();
  }

  // Includes FP instructions and bubbles. Used by CycleBuilder.
  public int getInstructionCount() {
    return pipe.size() + fpPipe.size();
  }

  /** Returns the number of cycles performed by the CPU.
   *  @return an integer
   */
  public int getCycles() {
    return cycles;
  }

  /** Returns the number of instructions executed by the CPU
   *  @return an integer
   */
  public int getInstructions() {
    return instructions;
  }

  /** Returns the number of RAW Stalls that happened inside the pipeline
   * @return an integer
   */
  public int getRAWStalls() {
    return RAWStalls;
  }

  /** Returns the number of WAW stalls that happened inside the pipeline
   * @return an integer
   */
  public int getWAWStalls() {
    return WAWStalls;
  }

  /** Returns the number of Structural Stalls (Divider not available) that happened inside the pipeline
   * @return an integer
   */
  public int getStructuralStallsDivider() {
    return dividerStalls;
  }

  /** Returns the number of Structural Stalls (Memory not available) that happened inside the pipeline
   * @return an integer
   */
  public int getStructuralStallsMemory() {
    return memoryStalls;
  }

  /** Returns the number of Structural Stalls (EX not available) that happened inside the pipeline
   * @return an integer
   */
  public int getStructuralStallsEX() {
    return exStalls;
  }

  /** Returns the number of Structural Stalls (FP Adder and FP Multiplier not available) that happened inside the pipeline
   * @return an integer
   */
  public int getStructuralStallsFuncUnit() {
    return funcUnitStalls;
  }

  /** Gets the floating point unit enabled exceptions
   *  @return true if exceptionName is enabled, false in the other case
   */
  public boolean getFPExceptions(FCSRRegister.FPExceptions exceptionName) {
    return FCSR.getFPExceptions(exceptionName);
  }

  /** Gets the Program Counter register
   *  @return a Register object
   */
  public Register getPC() {
    return pc;
  }
  /** Gets the Last Program Counter register
   *  @return a Register object
   */
  public Register getLastPC() {
    return old_pc;
  }

  /** Gets the LO register. It contains integer results of doubleword division
   * @return a Register object
   */
  public Register getLO() {
    return LO;
  }

  /** Gets the HI register. It contains integer results of doubleword division
   * @return a Register object
   */
  public Register getHI() {
    return HI;
  }

  /** Gets the structural stall counter
   *@return the memory stall counter
   */
  public int getMemoryStalls() {
    return memoryStalls;
  }

  /** This method performs a single pipeline step
  */
  public void step() throws AddressErrorException, HaltException, IrregularWriteOperationException, StoppedCPUException, MemoryElementNotFoundException, IrregularStringOfBitsException, TwosComplementSumException, SynchronousException, BreakException, NotAlignException {
    configFPExceptionsAndRM();

    String syncex;

    if (status != CPUStatus.RUNNING && status != CPUStatus.STOPPING) {
      throw new StoppedCPUException();
    }

    try {
      // Stages are executed from the last one (WB) to the first one (IF). After the
      // logic for the given stage is executed, the instruction is moved to the next
      // stage (except for WB, where the instruction is discarded.
      logger.info("\n\nStarting cycle " + ++cycles + "\n---------------------------------------------");

      // WB: Write-back stage.
      stepWB();

      // MEM: Memory access stage.
      stepMEM();

      // EX: Execution/effective address stage.
      // Returns the code of the synchronous exception that can happen at this
      // stage, so the rest of the step can continue and, at the end, the
      // exception can be thrown.
      syncex = stepEX();

      // ID: instruction decode / register fetch stage
      boolean rawException = stepID();
      if (rawException) {
        throw new RAWException();
      }

      // IF: instruction fetch stage.
      stepIF();

      if (syncex != null) {
        throw new SynchronousException(syncex);
      }
    } catch (JumpException ex) {
      logger.info("Executing a Jump.");
      try {
        if (!pipe.isEmpty(PipeStage.IF)) {
          logger.info("Executing the IF() method of the instruction in IF.");
          pipe.IF().IF();
        }
      } catch (BreakException bex) {
        logger.info("Caught a BREAK after a Jump: ignoring it.");
      }

      // A J-Type instruction has just modified the Program Counter. We need to
      // put in the IF stage the instruction the PC points to
      pipe.setIF(mem.getInstruction(pc));
      pipe.setEX(pipe.ID());
      pipe.setID(bubble);
      old_pc.writeDoubleWord((pc.getValue()));
      pc.writeDoubleWord((pc.getValue()) + 4);

    } catch (RAWException ex) {
      if (currentPipeStage == PipeStage.ID) {
        pipe.setEX(bubble);
      }

      RAWStalls++;
      logger.info("RAW stalls incremented to " + RAWStalls);

    } catch (WAWException ex) {
      logger.info(fpPipe.toString());

      if (currentPipeStage == PipeStage.ID) {
        pipe.setEX(bubble);
      }

      WAWStalls++;
      logger.info("WAW stalls incremented to " + RAWStalls);

    } catch (FPDividerNotAvailableException ex) {
      if (currentPipeStage == PipeStage.ID) {
        pipe.setEX(bubble);
      }

      dividerStalls++;

    } catch (FPFunctionalUnitNotAvailableException ex) {
      if (currentPipeStage == PipeStage.ID) {
        pipe.setEX(bubble);
      }

      funcUnitStalls++;

    } catch (EXNotAvailableException ex) {
      exStalls++;

    } catch (SynchronousException ex) {
      logger.info("Exception: " + ex.getCode());
      throw ex;
    } catch (HaltException ex) {
      pipe.setWB(null);
      throw ex;
    } finally {
      logger.info("End of cycle " + cycles + "\n---------------------------------------------\n" + pipeLineString() + "\n");
    }
  }

  private void changeStage(PipeStage newStatus) {
    logger.info(newStatus.toString() + " STAGE: " + pipe.get(newStatus) + "\n================================");
    currentPipeStage = newStatus;
  }

  // Individual stages, in execution order (WB, MEM, EX, ID, IF).
  private void stepWB() throws IrregularStringOfBitsException, HaltException {
    changeStage(PipeStage.WB);

    if (pipe.isEmpty(PipeStage.WB)) {
      return;
    }

    boolean terminatorInstrInWB = terminating.contains(pipe.WB().getRepr().getHexString());
    //we have to execute the WB method only if some conditions occur
    //the current instruction in WB is a terminating instruction and the fpPipe is working
    boolean notWBable = terminatorInstrInWB && !fpPipe.isEmpty();
    //the current instruction in WB is a terminating instruction, the fpPipe doesn't work because it has just issued an instruction and it is in the MEM stage
    notWBable = notWBable || (terminatorInstrInWB && !pipe.isBubble(PipeStage.MEM));

    if (!pipe.isBubble(PipeStage.WB)) {
      instructions++;
    }

    if (!notWBable) {
      logger.info("Executing WB() for " + pipe.WB());
      pipe.WB().WB();
    }

    // Move the instruction in WB out of the pipeline.
    logger.info("Instruction " + pipe.WB() + " has been completed. Removing it.");
    pipe.setWB(null);

    //if the pipeline is empty and it is into the stopping state (because a long latency instruction was executed) we can halt the cpu when computations finished
    if (isPipelinesEmpty() && getStatus() == CPUStatus.STOPPING) {
      logger.info("Pipeline is empty and we are in STOPPING --> going to HALTED.");
      setStatus(CPU.CPUStatus.HALTED);
      throw new HaltException();
    }
  }

  private void stepMEM() throws HaltException, NotAlignException, IrregularWriteOperationException, MemoryElementNotFoundException, AddressErrorException, IrregularStringOfBitsException {
    changeStage(PipeStage.MEM);

    if (!pipe.isEmpty(PipeStage.MEM)) {
      logger.info("Executing MEM() for " + pipe.MEM());
      pipe.MEM().MEM();
    }

    logger.info("Moving " + pipe.MEM() + " to WB");
    pipe.setWB(pipe.MEM());
    pipe.setMEM(null);
  }

  private String stepEX() throws SynchronousException, HaltException, NotAlignException, TwosComplementSumException, IrregularWriteOperationException, AddressErrorException, IrregularStringOfBitsException {
    changeStage(PipeStage.EX);

    // Used for exception handling
    boolean masked = config.getBoolean(ConfigKey.SYNC_EXCEPTIONS_MASKED);
    boolean terminate = config.getBoolean(ConfigKey.SYNC_EXCEPTIONS_TERMINATE);

    // Code of the synchronous exception that happens in EX.
    String syncex = null;

    InstructionInterface completedFpInstruction = fpPipe.getCompletedInstruction();
    InstructionInterface instruction;
    boolean shouldExecuteFP = completedFpInstruction != null;

    // if there will be a stall because a lot of instructions would fill the MEM stage, the EX()
    // method cannot be called because the integer instruction in EX cannot be moved.
    if (!shouldExecuteFP) {
      instruction = pipe.EX();
    } else {
      //a structural stall has to be raised if the EX stage contains an instruction different from a bubble or other fu's contain instructions (counter of structural stalls must be incremented)
      if (!pipe.isEmptyOrBubble(PipeStage.EX) ||fpPipe.getNReadyToExitInstr() > 1) {
        memoryStalls++;
      }
      instruction = completedFpInstruction;

      // Keep track of the last FP instruction in EX.
      lastFPInstructionInEx = instruction;
    }

    // Execute the instruction, and handle synchronous exceptions.
    if (instruction != null) {
      try {
        logger.info("Executing EX() for " + instruction);
        instruction.EX();
      } catch (SynchronousException e) {
        if (masked) {
          logger.info("[EXCEPTION] [MASKED] " + e.getCode());
        } else {
          if (terminate) {
            logger.info("Terminating due to an unmasked exception");
            throw new SynchronousException(e.getCode());
          } else {
            // We must complete this cycle, but we must notify the user.
            // If the syncex string is not null, the CPU code will throw
            // the exception at the end of the step
            syncex = e.getCode();
          }
        }
      }
    }

    InstructionInterface toMove = shouldExecuteFP ? lastFPInstructionInEx : pipe.EX();
    logger.info("Moving " + toMove + " to MEM");
    pipe.setMEM(toMove);
    if (!shouldExecuteFP) {
      pipe.setEX(null);
    }
    // Shift instructions in the fpPipe.
    fpPipe.step();

    // Return the code of the synchronous exception (if any).
    return syncex;
  }

  // Returns true if there is a RAW conflict, false otherwis3. See docs for Instruction.ID()
  // for an explanation of why it is the case.
  private boolean stepID() throws TwosComplementSumException, WAWException, IrregularStringOfBitsException, FPInvalidOperationException, BreakException, HaltException, IrregularWriteOperationException, JumpException, FPDividerNotAvailableException, FPFunctionalUnitNotAvailableException, EXNotAvailableException {
    changeStage(PipeStage.ID);

    if (!pipe.isEmpty(PipeStage.ID)) {
      boolean isFP = FPPipeline.Constants.fparithmetic.contains(pipe.get(PipeStage.ID).getName());

      // Check if the desired unit (FP or not) is available.
      if (isFP && (fpPipe.putInstruction(pipe.ID(), true) != 0)) {
        if (pipe.ID().getName().compareToIgnoreCase("DIV.D") == 0) {
          throw new FPDividerNotAvailableException();
        } else {
          throw new FPFunctionalUnitNotAvailableException();
        }
      } else if (!isFP && (!pipe.isEmpty(PipeStage.EX) && !pipe.isBubble(PipeStage.EX))) {
        throw new EXNotAvailableException();
      }

      logger.info("Executing ID() for " + pipe.ID());
      // Can change the CPU status from RUNNING to STOPPING.
      boolean rawException = pipe.ID().ID();
      if (rawException) {
        return true;
      }

      if (isFP) {
        logger.info("Moving " + pipe.ID() + " to the FP pipeline.");
        fpPipe.putInstruction(pipe.ID(), false);
      } else {
        logger.info("Moving " + pipe.ID() + " to EX");
        pipe.setEX(pipe.ID());
      }

      pipe.setID(null);
    }
    return false;
  }

  private void stepIF() throws IrregularStringOfBitsException, IrregularWriteOperationException, BreakException {
    // We don't have to execute any methods, but we must get the new
    // instruction from the symbol table.
    changeStage(PipeStage.IF);

    logger.info("CPU Status: " + status.name());

    boolean breaking = false;
    if (status == CPUStatus.RUNNING) {
      if (!pipe.isEmpty(PipeStage.IF)) {  //rispetto a dinmips scambia le load con le IF
        try {
          logger.info("Executing IF() for " + pipe.IF());
          pipe.IF().IF();
        } catch (BreakException exc) {
          breaking = true;
        }
      }

      logger.info("Moving " + pipe.IF() + " to ID");
      pipe.setID(pipe.IF());

      InstructionInterface next_if = mem.getInstruction(pc);
      logger.info("Fetched new instruction " + next_if);

      old_pc.writeDoubleWord((pc.getValue()));
      pc.writeDoubleWord((pc.getValue()) + 4);
      logger.info("New Program Counter value: " + pc.toString());
      logger.info("Putting " + next_if + "in IF.");
      pipe.setIF(next_if);
    } else {
      pipe.setID(bubble);
    }

    if (breaking) {
      logger.info("Re-throwing the BREAK exception");
      throw new BreakException();
    }
  }

  /** This method resets the CPU components (GPRs, memory,statistics,
   *   PC, pipeline and Symbol table).
   *   It resets also the Dinero Tracefile object associated to the current
   *   CPU.
   */
  public void reset() {
    // Reset CPU state.
    status = CPUStatus.READY;
    cycles = 0;
    instructions = 0;
    RAWStalls = 0;
    WAWStalls = 0;
    dividerStalls = 0;
    funcUnitStalls = 0;
    exStalls = 0;
    memoryStalls = 0;

    // Reset registers.
    for (int i = 0; i < 32; i++) {
      gpr[i].reset();
    }

    //reset FPRs
    for (int i = 0; i < 32; i++) {
      fpr[i].reset();
    }


    try {
      // Reset the FCSR condition codes.
      for (int cc = 0; cc < 8; cc++) {
        setFCSRConditionCode(cc, 0);
      }

      // Reset the FCSR flags.
      setFCSRFlags("V", 0);
      setFCSRFlags("O", 0);
      setFCSRFlags("U", 0);
      setFCSRFlags("Z", 0);

      // Reset the FCSR cause bits.
      setFCSRCause("V", 0);
      setFCSRCause("O", 0);
      setFCSRCause("U", 0);
      setFCSRCause("Z", 0);
    } catch (IrregularStringOfBitsException ex) {
      ex.printStackTrace();
    }

    LO.reset();
    HI.reset();

    // Reset program counter
    pc.reset();
    old_pc.reset();

    // Reset the memory.
    mem.reset();

    // Reset pipeline
    pipe.clear();
    // Reset FP pipeline
    fpPipe.reset();

    logger.info("CPU Resetted");
  }

  /** Test method that returns a string containing the status of the pipeline.
   * @return string representation of the pipeline status
   */
  public String pipeLineString() {
    String s = "";
    s += "IF:\t" + pipe.IF() + "\n";
    s += "ID:\t" + pipe.ID() + "\n";
    s += "EX:\t" + pipe.EX() + "\n";
    s += "MEM:\t" + pipe.MEM() + "\n";
    s += "WB:\t" + pipe.WB() + "\n";

    return s;
  }

  /** Test method that returns a string containing the values of every
   * register.
   * @return string representation of the register file contents
   */
  public String gprString() {
    StringBuilder s = new StringBuilder();

    int i = 0;

    for (Register r : gpr) {
      s.append("Register ").append(i++).append(":\t").append(r.toString()).append("\n");
    }

    return s.toString();
  }

  public boolean isEnableForwarding() {
    return config.getBoolean(ConfigKey.FORWARDING);
  }

  /** Test method that returns a string containing the values of every
   * FPR.
   * @return a string
   */
  private String fprString() {
    StringBuilder s = new StringBuilder();
    int i = 0;

    for (RegisterFP r: fpr) {
      s.append("FP Register ").append(i++).append(":\t").append(r.toString()).append("\n");
    }

    return s.toString();
  }

  private void configFPExceptionsAndRM() {
    try {
      FCSR.setFPExceptions(FCSRRegister.FPExceptions.INVALID_OPERATION, config.getBoolean(ConfigKey.FP_INVALID_OPERATION));
      FCSR.setFPExceptions(FCSRRegister.FPExceptions.OVERFLOW, config.getBoolean(ConfigKey.FP_OVERFLOW));
      FCSR.setFPExceptions(FCSRRegister.FPExceptions.UNDERFLOW, config.getBoolean(ConfigKey.FP_UNDERFLOW));
      FCSR.setFPExceptions(FCSRRegister.FPExceptions.DIVIDE_BY_ZERO, config.getBoolean(ConfigKey.FP_DIVIDE_BY_ZERO));

      //setting the rounding mode
      if (config.getBoolean(ConfigKey.FP_NEAREST)) {
        FCSR.setFCSRRoundingMode(FCSRRegister.FPRoundingMode.TO_NEAREST);
      } else if (config.getBoolean(ConfigKey.FP_TOWARDS_ZERO)) {
        FCSR.setFCSRRoundingMode(FCSRRegister.FPRoundingMode.TOWARD_ZERO);
      } else if (config.getBoolean(ConfigKey.FP_TOWARDS_PLUS_INFINITY)) {
        FCSR.setFCSRRoundingMode(FCSRRegister.FPRoundingMode.TOWARDS_PLUS_INFINITY);
      } else if (config.getBoolean(ConfigKey.FP_TOWARDS_MINUS_INFINITY)) {
        FCSR.setFCSRRoundingMode(FCSRRegister.FPRoundingMode.TOWARDS_MINUS_INFINITY);
      }
    } catch (IrregularStringOfBitsException ex) {
      Logger.getLogger(CPU.class.getName()).log(Level.SEVERE, null, ex);
    }

  }

  public String toString() {
    String s = "";
    s += mem.toString() + "\n";
    s += pipeLineString();
    s += gprString();
    s += fprString();
    return s;
  }

  /** Private class, representing the R0 register */
  // TODO: DEVE IMPOSTARE I SEMAFORI?????
  private class R0 extends Register {
    public R0() {
      super("R0");
    }
    public long getValue() {
      return (long) 0;
    }
    public String getBinString() {
      return "0000000000000000000000000000000000000000000000000000000000000000";
    }
    public String getHexString() {
      return "0000000000000000";
    }
    public void setBits(String bits, int start) {
    }
    public void writeByteUnsigned(int value) {}
    public void writeByte(int value, int offset) {}
    public void writeHalfUnsigned(int value) {}
    public void writeHalf(int value) {}
    public void writeHalf(int value, int offset) {}
    public void writeWordUnsigned(long value) {}
    public void writeWord(int value) {}
    public void writeWord(long value, int offset) {}
    public void writeDoubleWord(long value) {}

  }
}
