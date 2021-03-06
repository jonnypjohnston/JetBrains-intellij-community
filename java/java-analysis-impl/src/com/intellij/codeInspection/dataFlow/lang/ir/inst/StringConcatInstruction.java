// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInspection.dataFlow.lang.ir.inst;

import com.intellij.codeInspection.dataFlow.CustomMethodHandlers;
import com.intellij.codeInspection.dataFlow.DfaMemoryState;
import com.intellij.codeInspection.dataFlow.DfaPsiUtil;
import com.intellij.codeInspection.dataFlow.TypeConstraints;
import com.intellij.codeInspection.dataFlow.jvm.JvmSpecialField;
import com.intellij.codeInspection.dataFlow.rangeSet.LongRangeBinOp;
import com.intellij.codeInspection.dataFlow.types.DfIntType;
import com.intellij.codeInspection.dataFlow.types.DfType;
import com.intellij.codeInspection.dataFlow.value.DfaValue;
import com.intellij.codeInspection.dataFlow.value.DfaValueFactory;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.intellij.codeInspection.dataFlow.types.DfTypes.*;

public class StringConcatInstruction extends EvalInstruction {
  private final int myLastOperand;
  private final @NotNull PsiType myStringType;

  public StringConcatInstruction(@Nullable PsiExpression expression, @NotNull PsiType stringType) {
    this(expression, stringType, -1);
  }

  public StringConcatInstruction(@Nullable PsiExpression expression,
                                 @NotNull PsiType stringType,
                                 int lastOperand) {
    super(expression, 2);
    assert lastOperand == -1 || expression instanceof PsiPolyadicExpression;
    myLastOperand = lastOperand;
    myStringType = stringType;
  }

  /**
   * @return range inside the anchor which evaluates this instruction, or null if the whole anchor evaluates this instruction
   */
  @Override
  @Nullable
  public TextRange getExpressionRange() {
    return DfaPsiUtil.getRange(getExpression(), myLastOperand);
  }

  @Override
  public @NotNull DfaValue eval(@NotNull DfaValueFactory factory,
                                @NotNull DfaMemoryState state,
                                @NotNull DfaValue @NotNull ... arguments) {
    DfaValue left = arguments[0];
    DfaValue right = arguments[1];
    String leftString = state.getDfType(left).getConstantOfType(String.class);
    String rightString = state.getDfType(right).getConstantOfType(String.class);
    if (leftString != null && rightString != null &&
        leftString.length() + rightString.length() <= CustomMethodHandlers.MAX_STRING_CONSTANT_LENGTH_TO_TRACK) {
      return factory.fromDfType(concatenationResult(leftString + rightString, myStringType));
    }
    DfaValue leftLength = JvmSpecialField.STRING_LENGTH.createValue(factory, left);
    DfaValue rightLength = JvmSpecialField.STRING_LENGTH.createValue(factory, right);
    DfType leftRange = state.getDfType(leftLength);
    DfType rightRange = state.getDfType(rightLength);
    DfType resultRange = leftRange instanceof DfIntType ? ((DfIntType)leftRange).eval(rightRange, LongRangeBinOp.PLUS) : INT;
    DfType result = resultRange.isConst(0)
                    ? referenceConstant("", myStringType)
                    : JvmSpecialField.STRING_LENGTH.asDfType(resultRange).meet(TypeConstraints.exact(myStringType).asDfType());
    return factory.fromDfType(result);
  }

  public String toString() {
    return "STRING_CONCAT";
  }
}
