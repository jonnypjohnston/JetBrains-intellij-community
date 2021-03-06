/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInspection.dataFlow;

import com.intellij.codeInsight.Nullability;
import com.intellij.codeInspection.dataFlow.java.DfaExpressionFactory;
import com.intellij.codeInspection.dataFlow.java.JavaDfaInstructionVisitor;
import com.intellij.codeInspection.dataFlow.jvm.ControlTransferHandler;
import com.intellij.codeInspection.dataFlow.jvm.JvmPsiRangeSetUtil;
import com.intellij.codeInspection.dataFlow.jvm.JvmSpecialField;
import com.intellij.codeInspection.dataFlow.jvm.descriptors.ArrayElementDescriptor;
import com.intellij.codeInspection.dataFlow.jvm.problems.ArrayIndexProblem;
import com.intellij.codeInspection.dataFlow.jvm.problems.ArrayStoreProblem;
import com.intellij.codeInspection.dataFlow.jvm.problems.ClassCastProblem;
import com.intellij.codeInspection.dataFlow.jvm.problems.MutabilityProblem;
import com.intellij.codeInspection.dataFlow.lang.DfaInterceptor;
import com.intellij.codeInspection.dataFlow.lang.DfaLanguageSupport;
import com.intellij.codeInspection.dataFlow.lang.UnsatisfiedConditionProblem;
import com.intellij.codeInspection.dataFlow.lang.ir.ControlFlow;
import com.intellij.codeInspection.dataFlow.lang.ir.inst.*;
import com.intellij.codeInspection.dataFlow.rangeSet.LongRangeSet;
import com.intellij.codeInspection.dataFlow.types.*;
import com.intellij.codeInspection.dataFlow.value.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.ThreeState;
import com.siyeh.ig.psiutils.MethodUtils;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static com.intellij.codeInspection.dataFlow.types.DfTypes.*;
import static com.intellij.util.ObjectUtils.tryCast;

/**
 * @author peter
 */
public abstract class InstructionVisitor<EXPR extends PsiElement> {
  private static final Logger LOG = Logger.getInstance(JavaDfaInstructionVisitor.class);
  protected final @NotNull DfaLanguageSupport<EXPR> myLanguageSupport;
  protected final @NotNull DfaInterceptor<EXPR> myInterceptor;
  protected final boolean myStopAnalysisOnNpe;
  protected final Set<InstanceofInstruction> myUsefulInstanceofs = new HashSet<>();

  protected InstructionVisitor(@NotNull DfaLanguageSupport<EXPR> support,
                               @Nullable DfaInterceptor<EXPR> interceptor,
                               boolean stopAnalysisOnNpe) {
    myLanguageSupport = support;
    //noinspection unchecked
    myInterceptor = interceptor != null ? interceptor :
                    this instanceof DfaInterceptor ? (DfaInterceptor<EXPR>)this :
                    new DfaInterceptor<>() {};
    myStopAnalysisOnNpe = stopAnalysisOnNpe;
  }

  void pushExpressionResult(@NotNull DfaValue value,
                            @NotNull ExpressionPushingInstruction<?> instruction,
                            @NotNull DfaMemoryState state) {
    myLanguageSupport.processExpressionPush(myInterceptor, value, instruction, state);
    state.push(value);
  }

  protected void flushArrayOnUnknownAssignment(AssignInstruction instruction,
                                               DfaValueFactory factory,
                                               DfaValue dest,
                                               DfaMemoryState memState) {
    if (dest instanceof DfaVariableValue) return;
    PsiArrayAccessExpression arrayAccess =
      tryCast(PsiUtil.skipParenthesizedExprDown(instruction.getLExpression()), PsiArrayAccessExpression.class);
    if (arrayAccess != null) {
      PsiExpression array = arrayAccess.getArrayExpression();
      DfaValue value = DfaExpressionFactory.getExpressionDfaValue(factory, array);
      if (value instanceof DfaVariableValue) {
        for (DfaVariableValue qualified : ((DfaVariableValue)value).getDependentVariables().toArray(new DfaVariableValue[0])) {
          if (qualified.isFlushableByCalls()) {
            memState.flushVariable(qualified);
          }
        }
      }
    }
  }

  public DfaInstructionState[] visitMethodCall(final MethodCallInstruction instruction, final DataFlowRunner runner, final DfaMemoryState memState) {
    DfaValueFactory factory = runner.getFactory();
    DfaCallArguments callArguments = popCall(instruction, factory, memState);

    Set<DfaMemoryState> finalStates = new LinkedHashSet<>();

    PsiType qualifierType = getPsiType(memState, callArguments.myQualifier);
    PsiMethod realMethod = findSpecificMethod(instruction.getContext(), instruction.getTargetMethod(), qualifierType);
    DfaValue defaultResult = getMethodResultValue(instruction, callArguments, memState, factory, realMethod);
    DfaCallState initialState = new DfaCallState(memState, callArguments, defaultResult);
    Set<DfaCallState> currentStates = Collections.singleton(initialState);
    PsiExpression expression = instruction.getExpression();
    if (callArguments.myArguments != null && !(defaultResult.getDfType() instanceof DfConstantType)) {
      for (MethodContract contract : instruction.getContracts()) {
        currentStates = addContractResults(contract, currentStates, factory, finalStates, expression);
        if (currentStates.size() + finalStates.size() > runner.getComplexityLimit()) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Too complex contract on " + instruction.getContext() + ", skipping contract processing");
          }
          finalStates.clear();
          currentStates = Collections.singleton(initialState);
          break;
        }
      }
    }
    for (DfaCallState callState : currentStates) {
      callState.myMemoryState.push(defaultResult);
      finalStates.add(callState.myMemoryState);
    }

    DfaInstructionState[] result = new DfaInstructionState[finalStates.size()];
    int i = 0;
    for (DfaMemoryState state : finalStates) {
      if (expression != null) {
        onMethodCall(state.peek(), expression, callArguments, state);
      }
      callArguments.flush(state, factory, realMethod);
      pushExpressionResult(state.pop(), instruction, state);
      result[i++] = new DfaInstructionState(runner.getInstruction(instruction.getIndex() + 1), state);
    }
    return result;
  }

  protected @NotNull DfaCallArguments popCall(MethodCallInstruction instruction, DfaValueFactory factory, DfaMemoryState memState) {
    DfaValue[] argValues = popCallArguments(instruction, factory, memState);
    final DfaValue qualifier = popQualifier(instruction, memState, argValues);
    return new DfaCallArguments(qualifier, argValues, instruction.getMutationSignature());
  }

  private DfaValue @Nullable [] popCallArguments(MethodCallInstruction instruction,
                                                 DfaValueFactory factory,
                                                 DfaMemoryState memState) {
    final int argCount = instruction.getArgCount();

    PsiMethod method = instruction.getTargetMethod();
    boolean varargCall = instruction.isVarArgCall();
    DfaValue[] argValues = null;
    PsiParameterList paramList = null;
    if (method != null) {
      paramList = method.getParameterList();
      int paramCount = paramList.getParametersCount();
      if (paramCount == argCount || method.isVarArgs() && argCount >= paramCount - 1) {
        argValues = new DfaValue[paramCount];
        if (varargCall) {
          PsiType arrayType = Objects.requireNonNull(paramList.getParameter(paramCount - 1)).getType();
          DfType dfType = JvmSpecialField.ARRAY_LENGTH.asDfType(intValue(argCount - paramCount + 1))
            .meet(TypeConstraints.exact(arrayType).asDfType());
          argValues[paramCount - 1] = factory.fromDfType(dfType);
        }
      }
    }

    for (int i = 0; i < argCount; i++) {
      DfaValue arg = memState.pop();
      int paramIndex = argCount - i - 1;

      boolean parameterMayNotLeak =
        HardcodedContracts.isKnownNoParameterLeak(method) ||
        (instruction.getMutationSignature().isPure() ||
         instruction.getMutationSignature().equals(MutationSignature.pure().alsoMutatesArg(paramIndex))) &&
        !mayLeakFromType(typedObject(instruction.getResultType(), Nullability.UNKNOWN));
      if (!parameterMayNotLeak) {
        // If we write to local object only, it should not leak
        arg = dropLocality(arg, memState);
      }
      PsiElement anchor = instruction.getArgumentAnchor(paramIndex);
      if (instruction.getContext() instanceof PsiMethodReferenceExpression) {
        PsiMethodReferenceExpression methodRef = (PsiMethodReferenceExpression)instruction.getContext();
        if (paramList != null) {
          PsiParameter parameter = paramList.getParameter(paramIndex);
          if (parameter != null) {
            if (TypeConversionUtil.isPrimitiveAndNotNull(parameter.getType())) {
              arg = dereference(memState, arg, NullabilityProblemKind.unboxingMethodRefParameter.problem(methodRef, null));
            }
            arg = DfaUtil.boxUnbox(arg, parameter.getType());
          }
        }
        Nullability nullability = instruction.getArgRequiredNullability(paramIndex);
        if (nullability == Nullability.NOT_NULL) {
          arg = dereference(memState, arg, NullabilityProblemKind.passingToNotNullMethodRefParameter.problem(methodRef, null));
        } else if (nullability == Nullability.UNKNOWN) {
          checkNotNullable(memState, arg, NullabilityProblemKind.passingToNonAnnotatedMethodRefParameter.problem(methodRef, null));
        }
      }
      if (instruction.getMutationSignature().mutatesArg(paramIndex)) {
        DfType dfType = memState.getDfType(arg);
        if (!Mutability.fromDfType(dfType).canBeModified() &&
            // Empty array cannot be modified at all
            !memState.getDfType(JvmSpecialField.ARRAY_LENGTH.createValue(factory, arg)).equals(intValue(0))) {
          myInterceptor.onCondition(new MutabilityProblem(anchor, false), arg, ThreeState.YES, memState);
          if (dfType instanceof DfReferenceType) {
            memState.setDfType(arg, ((DfReferenceType)dfType).dropMutability().meet(Mutability.MUTABLE.asDfType()));
          }
        }
      }
      if (argValues != null && (paramIndex < argValues.length - 1 || !varargCall)) {
        argValues[paramIndex] = arg;
      }
    }
    return argValues;
  }

  private DfaValue popQualifier(@NotNull MethodCallInstruction instruction,
                                @NotNull DfaMemoryState memState,
                                DfaValue @Nullable [] argValues) {
    DfaValue value = memState.pop();
    if (instruction.getContext() instanceof PsiMethodReferenceExpression) {
      PsiMethodReferenceExpression context = (PsiMethodReferenceExpression)instruction.getContext();
      value = dereference(memState, value, NullabilityProblemKind.callMethodRefNPE.problem(context, null));
    }
    DfType dfType = memState.getDfType(value);
    if (instruction.getMutationSignature().mutatesThis() && !Mutability.fromDfType(dfType).canBeModified()) {
      PsiMethod method = instruction.getTargetMethod();
      // Inferred mutation annotation may infer mutates="this" if invisible state is mutated (e.g. cached hashCode is stored).
      // So let's conservatively skip the warning here. Such contract is still useful because it assures that nothing else is mutated.
      if (method != null && JavaMethodContractUtil.getContractInfo(method).isExplicit()) {
        myInterceptor.onCondition(new MutabilityProblem(instruction.getContext(), true), value, ThreeState.YES, memState);
        if (dfType instanceof DfReferenceType) {
          memState.setDfType(value, ((DfReferenceType)dfType).dropMutability().meet(Mutability.MUTABLE.asDfType()));
        }
      }
    }
    TypeConstraint constraint = TypeConstraint.fromDfType(dfType);
    if (!constraint.isArray() && (constraint.isComparedByEquals() || mayLeakThis(instruction, memState, argValues))) {
      value = dropLocality(value, memState);
    }
    return value;
  }

  public DfaInstructionState[] visitEval(EvalInstruction instruction, DataFlowRunner runner, DfaMemoryState memState) {
    int operands = instruction.getOperands();
    DfaValue[] args = new DfaValue[operands];
    for (int i = operands - 1; i >= 0; i--) {
      args[i] = memState.pop();
    }
    DfaValue value = instruction.eval(runner.getFactory(), memState, args);
    if (value instanceof DfaVariableValue && mayLeakFromType(value.getDfType())) {
      DfaVariableValue qualifier = ((DfaVariableValue)value).getQualifier();
      if (qualifier != null) {
        dropLocality(qualifier, memState);
      }
    }
    pushExpressionResult(value, instruction, memState);
    return nextInstruction(instruction, runner, memState);
  }

  private static boolean mayLeakThis(@NotNull MethodCallInstruction instruction,
                                     @NotNull DfaMemoryState memState, DfaValue @Nullable [] argValues) {
    MutationSignature signature = instruction.getMutationSignature();
    if (signature == MutationSignature.unknown()) return true;
    if (mayLeakFromType(typedObject(instruction.getResultType(), Nullability.UNKNOWN))) return true;
    if (argValues == null) {
      return signature.isPure() || signature.equals(MutationSignature.pure().alsoMutatesThis());
    }
    for (int i = 0; i < argValues.length; i++) {
      if (signature.mutatesArg(i)) {
        DfType type = memState.getDfType(argValues[i]);
        if (mayLeakFromType(type)) return true;
      }
    }
    return false;
  }

  private static boolean mayLeakFromType(@NotNull DfType type) {
    if (type == DfType.BOTTOM) return false;
    // Complex value from field or method return call may contain back-reference to the object, so
    // local value could leak. Do not drop locality only for some simple values.
    while (true) {
      TypeConstraint constraint = TypeConstraint.fromDfType(type);
      DfType arrayComponentType = constraint.getArrayComponentType();
      if (arrayComponentType == DfType.BOTTOM) {
        return !(type instanceof DfPrimitiveType) && !constraint.isExact(CommonClassNames.JAVA_LANG_STRING);
      }
      type = arrayComponentType;
    }
  }

  private Set<DfaCallState> addContractResults(MethodContract contract,
                                               Set<DfaCallState> states,
                                               DfaValueFactory factory,
                                               Set<DfaMemoryState> finalStates,
                                               PsiExpression expression) {
    if(contract.isTrivial()) {
      for (DfaCallState callState : states) {
        DfaValue result = contract.getReturnValue().getDfaValue(factory, callState);
        pushExpressionResult(result, new ResultOfInstruction(expression), callState.myMemoryState);
        finalStates.add(callState.myMemoryState);
      }
      return Collections.emptySet();
    }

    Set<DfaCallState> falseStates = new LinkedHashSet<>();

    for (DfaCallState callState : states) {
      DfaMemoryState state = callState.myMemoryState;
      DfaCallArguments arguments = callState.myCallArguments;
      for (ContractValue contractValue : contract.getConditions()) {
        DfaCondition condition = contractValue.makeCondition(factory, callState.myCallArguments);
        DfaMemoryState falseState = state.createCopy();
        DfaCondition falseCondition = condition.negate();
        if (contract.getReturnValue().isFail() ?
            falseState.applyCondition(falseCondition) :
            falseState.applyContractCondition(falseCondition)) {
          DfaCallArguments falseArguments = contractValue.updateArguments(arguments, true);
          falseStates.add(callState.withMemoryState(falseState).withArguments(falseArguments));
        }
        if (!state.applyContractCondition(condition)) {
          state = null;
          break;
        }
        arguments = contractValue.updateArguments(arguments, false);
      }
      if (state != null) {
        DfaValue result = contract.getReturnValue().getDfaValue(factory, callState.withArguments(arguments));
        state.push(result);
        finalStates.add(state);
      }
    }

    return falseStates;
  }

  private <T extends PsiElement> DfaValue dereference(DfaMemoryState memState,
                                                      DfaValue value,
                                                      @Nullable NullabilityProblemKind.NullabilityProblem<T> problem) {
    checkNotNullable(memState, value, problem);
    if (value instanceof DfaTypeValue) {
      DfType dfType = value.getDfType().meet(NOT_NULL_OBJECT);
      return value.getFactory().fromDfType(dfType == DfType.BOTTOM ? NOT_NULL_OBJECT : dfType);
    }
    if (memState.isNull(value) && problem != null && problem.getKind() == NullabilityProblemKind.nullableFunctionReturn) {
      return value.getFactory().fromDfType(NOT_NULL_OBJECT);
    }
    if (value instanceof DfaVariableValue) {
      DfType dfType = memState.getDfType(value);
      if (dfType == NULL) {
        memState.setDfType(value, NOT_NULL_OBJECT);
      }
      else {
        memState.meetDfType(value, NOT_NULL_OBJECT);
      }
    }
    return value;
  }

  @Contract("_, null, _ -> null; _, !null, _ -> !null")
  private static PsiMethod findSpecificMethod(PsiElement context,
                                              @Nullable PsiMethod method,
                                              @Nullable PsiType qualifierType) {
    if (method == null || qualifierType == null || !PsiUtil.canBeOverridden(method)) return method;
    PsiExpression qualifierExpression = null;
    if (context instanceof PsiMethodCallExpression) {
      qualifierExpression = ((PsiMethodCallExpression)context).getMethodExpression().getQualifierExpression();
    }
    else if (context instanceof PsiMethodReferenceExpression) {
      qualifierExpression = ((PsiMethodReferenceExpression)context).getQualifierExpression();
    }
    if (qualifierExpression instanceof PsiSuperExpression) return method; // non-virtual call
    return MethodUtils.findSpecificMethod(method, qualifierType);
  }

  private static @NotNull DfaValue getMethodResultValue(MethodCallInstruction instruction,
                                                        @NotNull DfaCallArguments callArguments,
                                                        DfaMemoryState state, DfaValueFactory factory, PsiMethod realMethod) {
    if (callArguments.myArguments != null) {
      PsiMethod method = instruction.getTargetMethod();
      if (method != null) {
        CustomMethodHandlers.CustomMethodHandler handler = CustomMethodHandlers.find(method);
        if (handler != null) {
          DfaValue value = handler.getMethodResultValue(callArguments, state, factory, method);
          if (value != null) {
            return value;
          }
        }
      }
    }
    DfaValue qualifierValue = callArguments.myQualifier;
    DfaValue precalculated = instruction.getPrecalculatedReturnValue();
    PsiType type = instruction.getResultType();

    SpecialField field = JvmSpecialField.findSpecialField(instruction.getTargetMethod());
    if (qualifierValue instanceof DfaWrappedValue && ((DfaWrappedValue)qualifierValue).getSpecialField() == field) {
      return ((DfaWrappedValue)qualifierValue).getWrappedValue();
    }
    if (precalculated != null) {
      return precalculated;
    }
    if (field != null) {
      return DfaUtil.boxUnbox(factory.fromDfType(field.getFromQualifier(state.getDfType(qualifierValue))), type);
    }

    if (instruction.getContext() instanceof PsiMethodReferenceExpression && qualifierValue instanceof DfaVariableValue) {
      PsiMethod method = instruction.getTargetMethod();
      VariableDescriptor descriptor = DfaExpressionFactory.getAccessedVariableOrGetter(method);
      if (descriptor != null) {
        return descriptor.createValue(factory, qualifierValue, true);
      }
    }

    if (type != null && !(type instanceof PsiPrimitiveType)) {
      Nullability nullability = instruction.getReturnNullability();
      PsiMethod targetMethod = instruction.getTargetMethod();
      Mutability mutable = Mutability.UNKNOWN;
      if (targetMethod != null) {
        if (realMethod != targetMethod) {
          nullability = DfaPsiUtil.getElementNullability(type, realMethod);
          mutable = Mutability.getMutability(realMethod);
        } else {
          mutable = Mutability.getMutability(targetMethod);
        }
        PsiType qualifierType = getPsiType(state, qualifierValue);
        type = narrowReturnType(type, qualifierType, realMethod);
      }
      DfType dfType = instruction.getContext() instanceof PsiNewExpression ?
                      TypeConstraints.exact(type).asDfType().meet(NOT_NULL_OBJECT) :
                      TypeConstraints.instanceOf(type).asDfType().meet(DfaNullability.fromNullability(nullability).asDfType());
      if (instruction.getMutationSignature().isPure() && instruction.getContext() instanceof PsiNewExpression &&
          !TypeConstraint.fromDfType(dfType).isComparedByEquals()) {
        dfType = dfType.meet(LOCAL_OBJECT);
      }
      return factory.fromDfType(dfType.meet(mutable.asDfType()));
    }
    LongRangeSet range = JvmPsiRangeSetUtil.typeRange(type);
    if (range != null) {
      PsiMethod method = instruction.getTargetMethod();
      if (method != null) {
        range = range.intersect(JvmPsiRangeSetUtil.fromPsiElement(method));
      }
      return factory.fromDfType(rangeClamped(range, PsiType.LONG.equals(type)));
    }
    return PsiType.VOID.equals(type) ? factory.getUnknown() : factory.fromDfType(typedObject(type, Nullability.UNKNOWN));
  }

  private static @NotNull PsiType narrowReturnType(@NotNull PsiType returnType, @Nullable PsiType qualifierType,
                                                   @NotNull PsiMethod realMethod) {
    PsiClass containingClass = realMethod.getContainingClass();
    PsiType realReturnType = realMethod.getReturnType();
    if (containingClass != null && qualifierType instanceof PsiClassType) {
      if (containingClass.hasTypeParameters() || containingClass.getContainingClass() != null) {
        PsiClassType.ClassResolveResult classResolveResult = ((PsiClassType)qualifierType).resolveGenerics();
        PsiClass subType = classResolveResult.getElement();
        if (subType != null && !subType.equals(containingClass)) {
          PsiSubstitutor substitutor = TypeConversionUtil
            .getMaybeSuperClassSubstitutor(containingClass, subType, classResolveResult.getSubstitutor());
          if (substitutor != null) {
            realReturnType = substitutor.substitute(realReturnType);
          }
        }
      }
    }
    if (realReturnType != null && !realReturnType.equals(returnType) &&
        TypeConversionUtil.erasure(returnType).isAssignableFrom(realReturnType)) {
      // possibly covariant return type
      return realReturnType;
    }
    return returnType;
  }

  private void checkNotNullable(DfaMemoryState state,
                                @NotNull DfaValue value,
                                @Nullable NullabilityProblemKind.NullabilityProblem<?> problem) {
    if (problem != null) {
      DfaNullability nullability = DfaNullability.fromDfType(state.getDfType(value));
      ThreeState failed = nullability == DfaNullability.NOT_NULL ? ThreeState.NO :
                          nullability == DfaNullability.NULL ? ThreeState.YES : ThreeState.UNSURE;
      myInterceptor.onCondition(problem, value, failed, state);
    }
  }

  public DfaInstructionState @NotNull [] visitControlTransfer(@NotNull ControlTransferInstruction controlTransferInstruction,
                                                              @NotNull DataFlowRunner runner, @NotNull DfaMemoryState state) {
    return ControlTransferHandler.dispatch(state, runner, controlTransferInstruction.getTransfer())
      .toArray(DfaInstructionState.EMPTY_ARRAY);
  }

  public DfaInstructionState[] visitEndOfInitializer(EndOfInitializerInstruction instruction, DataFlowRunner runner, DfaMemoryState state) {
    myInterceptor.beforeInitializerEnd(instruction.isStatic(), state);
    return nextInstruction(instruction, runner, state);
  }

  public DfaInstructionState[] visitAssign(AssignInstruction instruction, DataFlowRunner runner, DfaMemoryState memState) {
    DfaValue dfaSource = memState.pop();
    DfaValue dfaDest = memState.pop();

    if (!(dfaDest instanceof DfaVariableValue) && instruction.getAssignedValue() != null) {
      // It's possible that dfaDest on the stack is cleared to DfaTypeValue due to variable flush
      // (e.g. during StateMerger#mergeByFacts), so we try to restore the original destination.
      dfaDest = instruction.getAssignedValue();
    }
    myInterceptor.beforeAssignment(dfaSource, dfaDest, memState, instruction.getExpression());
    if (dfaSource == dfaDest) {
      memState.push(dfaDest);
      flushArrayOnUnknownAssignment(instruction, runner.getFactory(), dfaDest, memState);
      return nextInstruction(instruction, runner, memState);
    }
    if (!(dfaDest instanceof DfaVariableValue &&
          ((DfaVariableValue)dfaDest).getPsiVariable() instanceof PsiLocalVariable &&
          dfaSource instanceof DfaVariableValue &&
          (ControlFlow.isTempVariable((DfaVariableValue)dfaSource) ||
           ((DfaVariableValue)dfaSource).getDescriptor().isCall()))) {
      dropLocality(dfaSource, memState);
    }

    PsiExpression lValue = PsiUtil.skipParenthesizedExprDown(instruction.getLExpression());
    PsiExpression rValue = instruction.getRExpression();
    if (lValue instanceof PsiArrayAccessExpression) {
      checkArrayElementAssignability(memState, dfaSource, dfaDest, lValue, rValue);
    }

    if (dfaDest instanceof DfaVariableValue) {
      DfaVariableValue var = (DfaVariableValue) dfaDest;

      PsiElement psi = var.getPsiVariable();
      if (dfaSource instanceof DfaTypeValue &&
          ((psi instanceof PsiField && ((PsiField)psi).hasModifierProperty(PsiModifier.STATIC)) ||
           (var.getQualifier() != null && !DfReferenceType.isLocal(memState.getDfType(var.getQualifier()))))) {
        DfType dfType = dfaSource.getDfType();
        if (dfType instanceof DfReferenceType) {
          dfaSource = dfaSource.getFactory().fromDfType(((DfReferenceType)dfType).dropLocality());
        }
      }
      if (!(psi instanceof PsiField) || !((PsiField)psi).hasModifierProperty(PsiModifier.VOLATILE)) {
        memState.setVarValue(var, dfaSource);
      }
      if (DfaNullability.fromDfType(var.getInherentType()) == DfaNullability.NULLABLE &&
          DfaNullability.fromDfType(memState.getDfType(var)) == DfaNullability.UNKNOWN && instruction.isVariableInitializer()) {
        memState.meetDfType(var, DfaNullability.NULLABLE.asDfType());
      }
    }

    pushExpressionResult(dfaDest, instruction, memState);
    flushArrayOnUnknownAssignment(instruction, runner.getFactory(), dfaDest, memState);

    return nextInstruction(instruction, runner, memState);
  }

  private void checkArrayElementAssignability(@NotNull DfaMemoryState memState,
                                              @NotNull DfaValue dfaSource,
                                              @NotNull DfaValue dfaDest,
                                              @NotNull PsiExpression lValue,
                                              @Nullable PsiExpression rValue) {
    if (rValue == null) return;
    PsiType rCodeType = rValue.getType();
    PsiType lCodeType = lValue.getType();
    // If types known from source are not convertible, a compilation error is displayed, additional warning is unnecessary
    if (rCodeType == null || lCodeType == null || !TypeConversionUtil.areTypesConvertible(rCodeType, lCodeType)) return;
    if (!(dfaDest instanceof DfaVariableValue)) return;
    DfaVariableValue qualifier = ((DfaVariableValue)dfaDest).getQualifier();
    if (qualifier == null) return;
    DfType toType = TypeConstraint.fromDfType(memState.getDfType(qualifier)).getArrayComponentType();
    if (toType == DfType.BOTTOM) return;
    DfType fromType = memState.getDfType(dfaSource);
    DfType meet = fromType.meet(toType);
    Project project = lValue.getProject();
    PsiAssignmentExpression assignmentExpression = PsiTreeUtil.getParentOfType(rValue, PsiAssignmentExpression.class);
    PsiType psiFromType = TypeConstraint.fromDfType(fromType).getPsiType(project);
    PsiType psiToType = TypeConstraint.fromDfType(toType).getPsiType(project);
    if (assignmentExpression == null || psiFromType == null || psiToType == null) return;
    myInterceptor.onCondition(new ArrayStoreProblem(assignmentExpression, psiFromType, psiToType), dfaSource,
                              meet == DfType.BOTTOM ? ThreeState.YES : ThreeState.UNSURE, memState);
  }

  protected static DfaInstructionState[] nextInstruction(Instruction instruction, DataFlowRunner runner, DfaMemoryState memState) {
    return new DfaInstructionState[]{new DfaInstructionState(runner.getInstruction(instruction.getIndex() + 1), memState)};
  }

  public DfaInstructionState[] visitBinop(BooleanBinaryInstruction instruction, DataFlowRunner runner, DfaMemoryState memState) {
    DfaValue dfaRight = memState.pop();
    DfaValue dfaLeft = memState.pop();

    final IElementType opSign = instruction.getOperationSign();
    RelationType relationType =
      DfaPsiUtil.getRelationByToken(opSign == BooleanBinaryInstruction.STRING_EQUALITY_BY_CONTENT ? JavaTokenType.EQEQ : opSign);
    if (relationType != null) {
      return handleRelationBinop(instruction, runner, memState, dfaRight, dfaLeft, relationType);
    }
    return handleAndOrBinop(instruction, runner, memState, dfaRight, dfaLeft);
  }

  private DfaInstructionState @NotNull [] handleAndOrBinop(BooleanBinaryInstruction instruction,
                                                           DataFlowRunner runner,
                                                           DfaMemoryState memState,
                                                           DfaValue dfaRight, DfaValue dfaLeft) {
    IElementType opSign = instruction.getOperationSign();
    List<DfaInstructionState> result = new ArrayList<>(2);
    if (opSign == JavaTokenType.AND || opSign == JavaTokenType.OR) {
      boolean or = opSign == JavaTokenType.OR;
      DfaMemoryState copy = memState.createCopy();
      DfaCondition cond = dfaRight.eq(booleanValue(or));
      if (copy.applyCondition(cond)) {
        result.add(makeBooleanResult(instruction, runner, copy, ThreeState.fromBoolean(or)));
      }
      if (memState.applyCondition(cond.negate())) {
        pushExpressionResult(dfaLeft, instruction, memState);
        result.add(new DfaInstructionState(runner.getInstruction(instruction.getIndex() + 1), memState));
      }
    } else {
      result.add(makeBooleanResult(instruction, runner, memState, ThreeState.UNSURE));
    }
    return result.toArray(DfaInstructionState.EMPTY_ARRAY);
  }

  private DfaInstructionState @NotNull [] handleRelationBinop(BooleanBinaryInstruction instruction,
                                                              DataFlowRunner runner,
                                                              DfaMemoryState memState,
                                                              DfaValue dfaRight,
                                                              DfaValue dfaLeft,
                                                              RelationType relationType) {
    DfaValueFactory factory = runner.getFactory();
    if((relationType == RelationType.EQ || relationType == RelationType.NE) &&
       instruction.getOperationSign() != BooleanBinaryInstruction.STRING_EQUALITY_BY_CONTENT &&
       memState.shouldCompareByEquals(dfaLeft, dfaRight)) {
      ArrayList<DfaInstructionState> states = new ArrayList<>(2);
      DfaMemoryState equality = memState.createCopy();
      DfaCondition condition = dfaLeft.eq(dfaRight);
      if (equality.applyCondition(condition)) {
        states.add(makeBooleanResult(instruction, runner, equality, ThreeState.UNSURE));
      }
      if (memState.applyCondition(condition.negate())) {
        states.add(makeBooleanResult(instruction, runner, memState, ThreeState.fromBoolean(relationType == RelationType.NE)));
      }
      return states.toArray(DfaInstructionState.EMPTY_ARRAY);
    }
    RelationType[] relations = splitRelation(relationType);

    ArrayList<DfaInstructionState> states = new ArrayList<>(relations.length);

    for (int i = 0; i < relations.length; i++) {
      RelationType relation = relations[i];
      DfaCondition condition = dfaLeft.cond(relation, dfaRight);
      if (condition == DfaCondition.getFalse()) continue;
      if (condition == DfaCondition.getTrue()) {
        DfaInstructionState state =
          makeBooleanResult(instruction, runner, memState, ThreeState.fromBoolean(relationType.isSubRelation(relation)));
        return new DfaInstructionState[]{state};
      }
      final DfaMemoryState copy = i == relations.length - 1 && !states.isEmpty() ? memState : memState.createCopy();
      if (copy.applyCondition(condition)) {
        boolean isTrue = relationType.isSubRelation(relation);
        states.add(makeBooleanResult(instruction, runner, copy, ThreeState.fromBoolean(isTrue)));
      }
    }
    if (states.isEmpty()) {
      // Neither of relations could be applied: likely comparison with NaN; do not split the state in this case, just push false
      pushExpressionResult(factory.fromDfType(FALSE), instruction, memState);
      return nextInstruction(instruction, runner, memState);
    }

    return states.toArray(DfaInstructionState.EMPTY_ARRAY);
  }

  private static RelationType @NotNull [] splitRelation(RelationType relationType) {
    switch (relationType) {
      case LT:
      case LE:
      case GT:
      case GE:
        return new RelationType[]{RelationType.LT, RelationType.GT, RelationType.EQ};
      default:
        return new RelationType[]{relationType, relationType.getNegated()};
    }
  }

  public DfaInstructionState[] visitNot(NotInstruction instruction, DataFlowRunner runner, DfaMemoryState memState) {
    DfaValue dfaValue = memState.pop();

    DfaMemoryState falseState = memState.createCopy();
    DfaValueFactory factory = runner.getFactory();
    List<DfaInstructionState> result = new ArrayList<>(2);
    if (memState.applyCondition(dfaValue.eq(FALSE))) {
      pushExpressionResult(factory.fromDfType(TRUE), instruction, memState);
      result.add(new DfaInstructionState(runner.getInstruction(instruction.getIndex() + 1), memState));
    }
    if (falseState.applyCondition(dfaValue.eq(TRUE))) {
      pushExpressionResult(factory.fromDfType(FALSE), instruction, falseState);
      result.add(new DfaInstructionState(runner.getInstruction(instruction.getIndex() + 1), falseState));
    }

    return result.toArray(DfaInstructionState.EMPTY_ARRAY);
  }

  public DfaInstructionState[] visitConditionalGoto(ConditionalGotoInstruction instruction, DataFlowRunner runner, DfaMemoryState memState) {
    boolean value = !instruction.isNegated();
    DfaCondition condTrue = memState.pop().eq(booleanValue(value));
    DfaCondition condFalse = condTrue.negate();

    PsiElement anchor = instruction.getPsiAnchor();
    if (condTrue == DfaCondition.getTrue()) {
      if (anchor != null) {
        myInterceptor.beforeConditionalJump(anchor, true);
      }
      return new DfaInstructionState[] {new DfaInstructionState(runner.getInstruction(instruction.getOffset()), memState)};
    }

    if (condFalse == DfaCondition.getTrue()) {
      if (anchor != null) {
        myInterceptor.beforeConditionalJump(anchor, false);
      }
      return nextInstruction(instruction, runner, memState);
    }

    ArrayList<DfaInstructionState> result = new ArrayList<>(2);

    DfaMemoryState elseState = memState.createCopy();

    if (memState.applyCondition(condTrue)) {
      result.add(new DfaInstructionState(runner.getInstruction(instruction.getOffset()), memState));
      if (anchor != null) {
        myInterceptor.beforeConditionalJump(anchor, true);
      }
    }

    if (elseState.applyCondition(condFalse)) {
      result.add(new DfaInstructionState(runner.getInstruction(instruction.getIndex() + 1), elseState));
      if (anchor != null) {
        myInterceptor.beforeConditionalJump(anchor, false);
      }
    }

    return result.toArray(DfaInstructionState.EMPTY_ARRAY);
  }

  public DfaInstructionState[] visitArrayAccess(ArrayAccessInstruction instruction, DataFlowRunner runner, DfaMemoryState memState) {
    PsiArrayAccessExpression arrayExpression = instruction.getExpression();
    DfaValue index = memState.pop();
    DfaValue array = memState.pop();
    boolean alwaysOutOfBounds = !applyBoundsCheck(memState, array, index);
    if (arrayExpression != null) {
      ThreeState failed = alwaysOutOfBounds ? ThreeState.YES : ThreeState.UNSURE;
      myInterceptor.onCondition(new ArrayIndexProblem(arrayExpression), index, failed, memState);
    }
    if (alwaysOutOfBounds) {
      DfaControlTransferValue transfer = instruction.getOutOfBoundsExceptionTransfer();
      if (transfer != null) {
        List<DfaInstructionState> states = ControlTransferHandler.dispatch(memState, runner, transfer);
        for (DfaInstructionState state : states) {
          state.getMemoryState().markEphemeral();
        }
        return states.toArray(DfaInstructionState.EMPTY_ARRAY);
      }
      return DfaInstructionState.EMPTY_ARRAY;
    }

    DfaValue result = instruction.getValue();
    LongRangeSet rangeSet = DfIntType.extractRange(memState.getDfType(index));
    DfaValue arrayElementValue = ArrayElementDescriptor.getArrayElementValue(runner.getFactory(), array, rangeSet);
    if (!DfaTypeValue.isUnknown(arrayElementValue)) {
      result = arrayElementValue;
    }
    if (!(result instanceof DfaVariableValue) && array instanceof DfaVariableValue) {
      for (DfaVariableValue value : ((DfaVariableValue)array).getDependentVariables().toArray(new DfaVariableValue[0])) {
        if (value.getQualifier() == array) {
          dropLocality(value, memState);
        }
      }
    }
    pushExpressionResult(result, instruction, memState);
    return nextInstruction(instruction, runner, memState);
  }

  private static boolean applyBoundsCheck(@NotNull DfaMemoryState memState,
                                          @NotNull DfaValue array,
                                          @NotNull DfaValue index) {
    DfaValueFactory factory = index.getFactory();
    DfaValue length = JvmSpecialField.ARRAY_LENGTH.createValue(factory, array);
    DfaCondition lengthMoreThanZero = length.cond(RelationType.GT, intValue(0));
    if (!memState.applyCondition(lengthMoreThanZero)) return false;
    DfaCondition indexNonNegative = index.cond(RelationType.GE, intValue(0));
    if (!memState.applyCondition(indexNonNegative)) return false;
    DfaCondition indexLessThanLength = index.cond(RelationType.LT, length);
    if (!memState.applyCondition(indexLessThanLength)) return false;
    return true;
  }

  public DfaInstructionState[] visitFlushVariable(FlushVariableInstruction instruction, DataFlowRunner runner, DfaMemoryState memState) {
    memState.flushVariable(instruction.getVariable());
    return nextInstruction(instruction, runner, memState);
  }

  public DfaInstructionState[] visitFlushFields(FlushFieldsInstruction instruction, DataFlowRunner runner, DfaMemoryState memState) {
    memState.flushFields();
    return nextInstruction(instruction, runner, memState);
  }

  public DfaInstructionState[] visitTypeCast(TypeCastInstruction instruction, DataFlowRunner runner, DfaMemoryState memState) {
    PsiType type = instruction.getCastTo();
    DfaControlTransferValue transfer = instruction.getCastExceptionTransfer();
    final DfaValueFactory factory = runner.getFactory();
    PsiType fromType = instruction.getCasted().getType();
    TypeConstraint constraint = TypeConstraints.instanceOf(type);
    boolean castPossible = true;
    List<DfaInstructionState> result = new ArrayList<>();
    if (transfer != null) {
      DfaMemoryState castFail = memState.createCopy();
      if (fromType != null && type.isConvertibleFrom(fromType)) {
        if (!castTopOfStack(factory, memState, constraint)) {
          castPossible = false;
        } else {
          result.add(new DfaInstructionState(runner.getInstruction(instruction.getIndex() + 1), memState));
          DfaValue value = memState.pop();
          pushExpressionResult(value, instruction, memState);
        }
      }
      DfaValue value = castFail.peek();
      DfaCondition notNullCondition = value.cond(RelationType.NE, NULL);
      DfaCondition notTypeCondition = value.cond(RelationType.IS_NOT, typedObject(type, Nullability.NOT_NULL));
      if (castFail.applyCondition(notNullCondition) && castFail.applyCondition(notTypeCondition)) {
        List<DfaInstructionState> states = ControlTransferHandler.dispatch(castFail, runner, transfer);
        for (DfaInstructionState cceState : states) {
          cceState.getMemoryState().markEphemeral();
        }
        result.addAll(states);
      }
    } else {
      if (fromType != null && type.isConvertibleFrom(fromType)) {
        if (!castTopOfStack(factory, memState, constraint)) {
          castPossible = false;
        }
      }

      result.add(new DfaInstructionState(runner.getInstruction(instruction.getIndex() + 1), memState));
      DfaValue value = memState.pop();
      pushExpressionResult(value, instruction, memState);
    }
    PsiTypeCastExpression expression = instruction.getExpression();
    if (expression != null) {
      myInterceptor.onCondition(new ClassCastProblem(expression), memState.peek(), castPossible ? ThreeState.UNSURE : ThreeState.YES,
                                memState);
    }
    return result.toArray(DfaInstructionState.EMPTY_ARRAY);
  }

  private static boolean castTopOfStack(@NotNull DfaValueFactory factory,
                                        @NotNull DfaMemoryState state,
                                        @NotNull TypeConstraint type) {
    DfaValue value = state.peek();
    DfType dfType = state.getDfType(value);
    DfType result = dfType.meet(type.asDfType());
    if (!result.equals(dfType)) {
      if (result == NULL || !state.meetDfType(value, result)) return false;
      if (!(value instanceof DfaVariableValue)) {
        state.pop();
        state.push(factory.fromDfType(result));
      }
    }
    return true;
  }

  protected void onMethodCall(@NotNull DfaValue result,
                              @NotNull PsiExpression expression,
                              @NotNull DfaCallArguments arguments,
                              @NotNull DfaMemoryState memState) {

  }

  public DfaInstructionState[] visitCheckNotNull(CheckNotNullInstruction instruction, DataFlowRunner runner, DfaMemoryState memState) {
    NullabilityProblemKind.NullabilityProblem<?> problem = instruction.getProblem();
    if (problem.thrownException() == null) {
      checkNotNullable(memState, memState.peek(), problem);
    } else {
      DfaControlTransferValue transfer = instruction.getOnNullTransfer();
      DfaValue value = memState.pop();
      boolean isNull = myStopAnalysisOnNpe && memState.isNull(value);
      if (transfer == null) {
        memState.push(dereference(memState, value, problem));
        if (isNull) {
          return DfaInstructionState.EMPTY_ARRAY;
        }
      } else {
        List<DfaInstructionState> result = new ArrayList<>();
        DfaMemoryState nullState = memState.createCopy();
        memState.push(dereference(memState, value, problem));
        if (!isNull) {
          result.add(new DfaInstructionState(runner.getInstruction(instruction.getIndex() + 1), memState));
        }
        if (nullState.applyCondition(value.eq(NULL))) {
          List<DfaInstructionState> dispatched = ControlTransferHandler.dispatch(nullState, runner, transfer);
          for (DfaInstructionState npeState : dispatched) {
            npeState.getMemoryState().markEphemeral();
          }
          result.addAll(dispatched);
        }
        return result.toArray(DfaInstructionState.EMPTY_ARRAY);
      }
    }
    return nextInstruction(instruction, runner, memState);
  }

  public DfaInstructionState[] visitEscapeInstruction(EscapeInstruction instruction, DataFlowRunner runner, DfaMemoryState state) {
    instruction.getEscapedVars().forEach(var -> dropLocality(var, state));
    return nextInstruction(instruction, runner, state);
  }

  private static DfaValue dropLocality(DfaValue value, DfaMemoryState state) {
    if (!(value instanceof DfaVariableValue)) {
      if (DfReferenceType.isLocal(value.getDfType())) {
        DfReferenceType dfType = ((DfReferenceType)value.getDfType()).dropLocality();
        if (value instanceof DfaWrappedValue) {
          return value.getFactory().getWrapperFactory()
            .createWrapper(dfType, ((DfaWrappedValue)value).getSpecialField(), ((DfaWrappedValue)value).getWrappedValue());
        }
        return value.getFactory().fromDfType(dfType);
      }
      return value;
    }
    DfaVariableValue var = (DfaVariableValue)value;
    DfType dfType = state.getDfType(var);
    if (dfType instanceof DfReferenceType) {
      state.setDfType(var, ((DfReferenceType)dfType).dropLocality());
    }
    for (DfaVariableValue v : new ArrayList<>(var.getDependentVariables())) {
      dfType = state.getDfType(v);
      if (dfType instanceof DfReferenceType) {
        state.setDfType(v, ((DfReferenceType)dfType).dropLocality());
      }
    }
    return value;
  }

  public DfaInstructionState[] visitEnsure(@NotNull EnsureInstruction instruction,
                                           @NotNull DataFlowRunner runner,
                                           @NotNull DfaMemoryState memState) {
    DfaValue tosValue = memState.isEmptyStack() ? runner.getFactory().getUnknown() : memState.peek();
    DfaControlTransferValue transfer = instruction.getExceptionTransfer();
    DfaCondition cond = instruction.createCondition(tosValue);
    Instruction nextInstruction = runner.getInstruction(instruction.getIndex() + 1);
    DfaInstructionState nextState = new DfaInstructionState(nextInstruction, memState);
    UnsatisfiedConditionProblem problem = instruction.getProblem();
    if (cond.equals(DfaCondition.getTrue())) {
      if (problem != null) {
        myInterceptor.onCondition(problem, tosValue, ThreeState.NO, memState);
      }
      return new DfaInstructionState[]{nextState};
    }
    if (transfer == null) {
      boolean satisfied = memState.applyCondition(cond);
      if (problem != null) {
        myInterceptor.onCondition(problem, tosValue, satisfied ? ThreeState.UNSURE : ThreeState.YES, memState);
      }
      if (!satisfied) {
        return DfaInstructionState.EMPTY_ARRAY;
      }
      return new DfaInstructionState[]{nextState};
    }
    DfaMemoryState falseState = memState.createCopy();
    boolean trueStatePossible = memState.applyCondition(cond);
    boolean falseStatePossible = falseState.applyCondition(cond.negate());
    List<DfaInstructionState> result = new ArrayList<>();
    if (trueStatePossible) {
      result.add(nextState);
    }
    if (problem != null) {
      myInterceptor.onCondition(problem, tosValue,
                                !trueStatePossible ? ThreeState.YES :
                                !falseStatePossible ? ThreeState.NO : ThreeState.UNSURE, memState);
    }
    if (falseStatePossible) {
      List<DfaInstructionState> states = ControlTransferHandler.dispatch(falseState, runner, transfer);
      if (instruction.isMakeEphemeral()) {
        for (DfaInstructionState negState : states) {
          negState.getMemoryState().markEphemeral();
        }
      }
      result.addAll(states);
    }
    return result.toArray(DfaInstructionState.EMPTY_ARRAY);
  }

  public DfaInstructionState[] visitClosureInstruction(ClosureInstruction instruction, DataFlowRunner runner, DfaMemoryState before) {
    for (PsiElement element : instruction.getClosureElements()) {
      runner.createClosureState(element, before);
    }
    return nextInstruction(instruction, runner, before);
  }

  public DfaInstructionState[] visitInstanceof(InstanceofInstruction instruction, DataFlowRunner runner, DfaMemoryState memState) {
    DfaValue dfaRight = memState.pop();
    DfaValue dfaLeft = memState.pop();
    DfaValueFactory factory = runner.getFactory();
    boolean unknownTargetType = false;
    DfaCondition condition = null;
    if (instruction.isClassObjectCheck()) {
      PsiType type = memState.getDfType(dfaRight).getConstantOfType(PsiType.class);
      if (type == null || type instanceof PsiPrimitiveType) {
        // Unknown/primitive class: just execute contract "null -> false"
        condition = dfaLeft.cond(RelationType.NE, NULL);
        unknownTargetType = true;
      } else {
        dfaRight = factory.fromDfType(typedObject(type, Nullability.NOT_NULL));
      }
    }
    if (condition == null) {
      condition = dfaLeft.cond(RelationType.IS, dfaRight);
    }

    boolean useful;
    ArrayList<DfaInstructionState> states = new ArrayList<>(2);
    DfType leftType = memState.getDfType(dfaLeft);
    if (condition.isUnknown()) {
      if (leftType != DfType.TOP && dfaLeft instanceof DfaTypeValue && dfaRight instanceof DfaTypeValue) {
        TypeConstraint left = TypeConstraint.fromDfType(leftType);
        TypeConstraint right = TypeConstraint.fromDfType(dfaRight.getDfType());
        useful = !right.isSuperConstraintOf(left);
      } else {
        useful = true;
      }
      states.add(makeBooleanResult(instruction, runner, memState, ThreeState.UNSURE));
    }
    else {
      final DfaMemoryState trueState = memState.createCopy();
      useful = unknownTargetType;
      if (trueState.applyCondition(condition)) {
        states.add(makeBooleanResult(instruction, runner, trueState, unknownTargetType ? ThreeState.UNSURE : ThreeState.YES));
      }
      DfaCondition negated = condition.negate();
      if (unknownTargetType ? memState.applyContractCondition(negated) : memState.applyCondition(negated)) {
        states.add(makeBooleanResult(instruction, runner, memState, ThreeState.NO));
        if (!memState.isNull(dfaLeft)) {
          useful = true;
        } else if (DfaNullability.fromDfType(leftType) == DfaNullability.UNKNOWN) {
          // Not-instanceof check leaves only "null" possible value in some state: likely the state is ephemeral
          memState.markEphemeral();
        }
      }
    }
    if (useful) {
      myUsefulInstanceofs.add(instruction);
    }
    return states.toArray(DfaInstructionState.EMPTY_ARRAY);
  }

  private DfaInstructionState makeBooleanResult(ExpressionPushingInstruction<?> instruction,
                                                DataFlowRunner runner,
                                                DfaMemoryState memState,
                                                @NotNull ThreeState result) {
    DfaValue value = result == ThreeState.UNSURE ? runner.getFactory().getUnknown() : runner.getFactory()
      .fromDfType(booleanValue(result.toBoolean()));
    pushExpressionResult(value, instruction, memState);
    return new DfaInstructionState(runner.getInstruction(instruction.getIndex() + 1), memState);
  }

  public DfaInstructionState[] visitMethodReference(MethodReferenceInstruction instruction, DataFlowRunner runner, DfaMemoryState memState) {
    PsiMethodReferenceExpression expression = instruction.getExpression();
    final DfaValue qualifier = memState.pop();
    dropLocality(qualifier, memState);
    handleMethodReference(qualifier, expression, runner, memState);
    pushExpressionResult(runner.getFactory().fromDfType(typedObject(expression.getFunctionalInterfaceType(), Nullability.NOT_NULL)), instruction, memState);

    return nextInstruction(instruction, runner, memState);
  }

  private void handleMethodReference(DfaValue qualifier,
                                     PsiMethodReferenceExpression methodRef,
                                     DataFlowRunner runner,
                                     DfaMemoryState state) {
    PsiType functionalInterfaceType = methodRef.getFunctionalInterfaceType();
    if (functionalInterfaceType == null) return;
    PsiMethod sam = LambdaUtil.getFunctionalInterfaceMethod(functionalInterfaceType);
    if (sam == null || PsiType.VOID.equals(sam.getReturnType())) return;
    JavaResolveResult resolveResult = methodRef.advancedResolve(false);
    PsiMethod method = tryCast(resolveResult.getElement(), PsiMethod.class);
    if (method == null || !JavaMethodContractUtil.isPure(method)) return;
    List<? extends MethodContract> contracts = JavaMethodContractUtil.getMethodCallContracts(method, null);
    PsiSubstitutor substitutor = resolveResult.getSubstitutor();
    DfaCallArguments callArguments = getMethodReferenceCallArguments(methodRef, qualifier, runner, sam, method, substitutor);
    dereference(state, callArguments.myQualifier, NullabilityProblemKind.callMethodRefNPE.problem(methodRef, null));
    if (contracts.isEmpty()) return;
    PsiType returnType = substitutor.substitute(method.getReturnType());
    DfaValue defaultResult = runner.getFactory().fromDfType(typedObject(returnType, DfaPsiUtil.getElementNullability(returnType, method)));
    Set<DfaCallState> currentStates = Collections.singleton(new DfaCallState(state.createClosureState(), callArguments, defaultResult));
    for (MethodContract contract : contracts) {
      Set<DfaMemoryState> results = new HashSet<>();
      currentStates = addContractResults(contract, currentStates, runner.getFactory(), results, methodRef);
      for (DfaMemoryState result : results) {
        pushExpressionResult(result.pop(), new ResultOfInstruction(methodRef), result);
      }
    }
    for (DfaCallState currentState: currentStates) {
      pushExpressionResult(defaultResult, new ResultOfInstruction(methodRef), currentState.myMemoryState);
    }
  }

  private static @NotNull DfaCallArguments getMethodReferenceCallArguments(PsiMethodReferenceExpression methodRef,
                                                                           DfaValue qualifier,
                                                                           DataFlowRunner runner,
                                                                           PsiMethod sam,
                                                                           PsiMethod method,
                                                                           PsiSubstitutor substitutor) {
    PsiParameter[] samParameters = sam.getParameterList().getParameters();
    boolean isStatic = method.hasModifierProperty(PsiModifier.STATIC);
    boolean instanceBound = !isStatic && !PsiMethodReferenceUtil.isStaticallyReferenced(methodRef);
    PsiParameter[] parameters = method.getParameterList().getParameters();
    DfaValue[] arguments = new DfaValue[parameters.length];
    Arrays.fill(arguments, runner.getFactory().getUnknown());
    for (int i = 0; i < samParameters.length; i++) {
      DfaValue value = runner.getFactory().fromDfType(
        typedObject(substitutor.substitute(samParameters[i].getType()), DfaPsiUtil.getFunctionalParameterNullability(methodRef, i)));
      if (i == 0 && !isStatic && !instanceBound) {
        qualifier = value;
      }
      else {
        int idx = i - ((isStatic || instanceBound) ? 0 : 1);
        if (idx >= arguments.length) break;
        if (!(parameters[idx].getType() instanceof PsiEllipsisType)) {
          arguments[idx] = value;
        }
      }
    }
    return new DfaCallArguments(qualifier, arguments, MutationSignature.fromMethod(method));
  }

  /**
   * @param state memory state
   * @param value value
   * @return PsiType of the value within given memory state
   */
  private static @Nullable PsiType getPsiType(DfaMemoryState state, @NotNull DfaValue value) {
    return DfaPsiUtil.dfTypeToPsiType(value.getFactory().getProject(), state.getDfType(value));
  }
}
