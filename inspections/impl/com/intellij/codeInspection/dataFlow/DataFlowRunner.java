/*
 * Created by IntelliJ IDEA.
 * User: max
 * Date: Jan 28, 2002
 * Time: 10:16:39 PM
 * To change template for new class use 
 * Code Style | Class Templates options (Tools | IDE Options).
 */
package com.intellij.codeInspection.dataFlow;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.codeInspection.dataFlow.instructions.BranchingInstruction;
import com.intellij.codeInspection.dataFlow.instructions.Instruction;
import com.intellij.codeInspection.dataFlow.value.DfaValueFactory;
import com.intellij.codeInspection.dataFlow.value.DfaVariableValue;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;

import java.util.ArrayList;
import java.util.EmptyStackException;
import java.util.HashSet;
import java.util.Set;

public class DataFlowRunner {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInspection.dataFlow.DataFlowRunner");
  private static final long ourTimeLimit = 10000;

  private Instruction[] myInstructions;
  private DfaVariableValue[] myFields;
  private final DfaValueFactory myValueFactory;
  private final InstructionFactory myInstructionFactory;

  // Maximum allowed attempts to process instruction. Fail as too complex to process if certain instruction
  // is executed more than this limit times.
  public static final int MAX_STATES_PER_BRANCH = 300;

  public Instruction getInstruction(int index) {
    return myInstructions[index];
  }

  protected DataFlowRunner(final InstructionFactory instructionFactory) {
    myInstructionFactory = instructionFactory;
    myValueFactory = new DfaValueFactory();
  }

  public DfaValueFactory getFactory() {
    return myValueFactory;
  }

  protected InstructionFactory getInstructionFactory() {
    return myInstructionFactory;
  }

  public RunnerResult analyzeMethod(PsiElement psiBlock, InstructionVisitor visitor) {
    final boolean isInMethod = psiBlock.getParent() instanceof PsiMethod;

    try {
      final ControlFlowAnalyzer analyzer = createControlFlowAnalyzer();
      final ControlFlow flow = analyzer.buildControlFlow(psiBlock);
      if (flow == null) return RunnerResult.NOT_APPLICABLE;

      int endOffset = flow.getInstructionCount();
      myInstructions = flow.getInstructions();
      myFields = flow.getFields();

      if (LOG.isDebugEnabled()) {
        for (int i = 0; i < myInstructions.length; i++) {
          Instruction instruction = myInstructions[i];
          LOG.debug(i + ": " + instruction.toString());
        }
      }

      int branchCount = 0;
      for (Instruction instruction : myInstructions) {
        if (instruction instanceof BranchingInstruction) branchCount++;
      }

      if (branchCount > 80) return RunnerResult.TOO_COMPLEX; // Do not even try. Definetly will out of time.

      final ArrayList<DfaInstructionState> queue = new ArrayList<DfaInstructionState>();
      final DfaMemoryState initialState = createMemoryState();

      if (isInMethod) {
        PsiMethod method = (PsiMethod)psiBlock.getParent();
        final PsiParameter[] parameters = method.getParameterList().getParameters();
        for (PsiParameter parameter : parameters) {
          if (AnnotationUtil.isNotNull(parameter)) {
            initialState.applyNotNull(myValueFactory.getVarFactory().create(parameter, false));
          }
        }
      }

      queue.add(new DfaInstructionState(myInstructions[0], initialState));
      long timeLimit = ourTimeLimit;
      final boolean unitTestMode = ApplicationManager.getApplication().isUnitTestMode();
      final long before = System.currentTimeMillis();
      while (!queue.isEmpty()) {
        if (!unitTestMode && System.currentTimeMillis() - before > timeLimit) return RunnerResult.TOO_COMPLEX;
        ProgressManager.getInstance().checkCanceled();

        DfaInstructionState instructionState = queue.remove(0);
        if (LOG.isDebugEnabled()) {
          LOG.debug(instructionState.toString());
        }

        Instruction instruction = instructionState.getInstruction();
        long distance = instructionState.getDistanceFromStart();

        if (instruction instanceof BranchingInstruction) {
          if (!instruction.setMemoryStateProcessed(instructionState.getMemoryState().createCopy())) {
            return RunnerResult.TOO_COMPLEX; // Too complex :(
          }
        }

        DfaInstructionState[] after = instruction.accept(this, instructionState.getMemoryState(), visitor);
        if (after != null) {
          for (DfaInstructionState state : after) {
            Instruction nextInstruction = state.getInstruction();
            if ((!(nextInstruction instanceof BranchingInstruction) || !nextInstruction.isMemoryStateProcessed(state.getMemoryState())) && instruction.getIndex() < endOffset) {
              state.setDistanceFromStart(distance + 1);
              queue.add(state);
            }
          }
        }
      }

      return RunnerResult.OK;
    }
    catch (ArrayIndexOutOfBoundsException e) {
      LOG.error(psiBlock.getText(), e); /* TODO[max] !!! hack (of 18186). Please fix in better times. */
      return RunnerResult.ABORTED;
    }
    catch (EmptyStackException e) /* TODO[max] !!! hack (of 18186). Please fix in better times. */ {
      return RunnerResult.ABORTED;
    }
  }

  protected ControlFlowAnalyzer createControlFlowAnalyzer() {
    return new ControlFlowAnalyzer(myValueFactory, myInstructionFactory);
  }

  protected DfaMemoryState createMemoryState() {
    return new DfaMemoryStateImpl(myValueFactory);
  }

  public Instruction[] getInstructions() {
    return myInstructions;
  }

  public DfaVariableValue[] getFields() {
    return myFields;
  }

  public Pair<Set<Instruction>,Set<Instruction>> getConstConditionalExpressions() {
    Set<Instruction> trueSet = new HashSet<Instruction>();
    Set<Instruction> falseSet = new HashSet<Instruction>();

    for (Instruction instruction : myInstructions) {
      if (instruction instanceof BranchingInstruction) {
        BranchingInstruction branchingInstruction = (BranchingInstruction)instruction;
        if (branchingInstruction.getPsiAnchor() != null && branchingInstruction.isConditionConst()) {
          if (!branchingInstruction.isTrueReachable()) {
            falseSet.add(branchingInstruction);
          }

          if (!branchingInstruction.isFalseReachable()) {
            trueSet.add(branchingInstruction);
          }
        }
      }
    }

    for (Instruction instruction : myInstructions) {
      if (instruction instanceof BranchingInstruction) {
        BranchingInstruction branchingInstruction = (BranchingInstruction)instruction;
        if (branchingInstruction.isTrueReachable()) {
          falseSet.remove(branchingInstruction);
        }
        if (branchingInstruction.isFalseReachable()) {
          trueSet.remove(branchingInstruction);
        }
      }
    }

    return Pair.create(trueSet, falseSet);
  }

}
