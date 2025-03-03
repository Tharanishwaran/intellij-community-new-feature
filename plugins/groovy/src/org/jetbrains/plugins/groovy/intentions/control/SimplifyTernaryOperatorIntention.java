/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package org.jetbrains.plugins.groovy.intentions.control;

import com.intellij.modcommand.ActionContext;
import com.intellij.modcommand.ModPsiUpdater;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.groovy.intentions.base.PsiElementPredicate;
import org.jetbrains.plugins.groovy.intentions.base.GrPsiUpdateIntention;
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElementFactory;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrConditionalExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression;
import org.jetbrains.plugins.groovy.lang.psi.impl.utils.ParenthesesUtils;
import org.jetbrains.plugins.groovy.lang.psi.util.GroovyConstantExpressionEvaluator;

/**
 * @author Niels Harremoes
 * @author Oscar Toernroth
 */
public class SimplifyTernaryOperatorIntention extends GrPsiUpdateIntention {

  @Override
  protected void processIntention(@NotNull PsiElement element, @NotNull ActionContext context, @NotNull ModPsiUpdater updater) {
    if (!(element instanceof GrConditionalExpression condExp)) {
      throw new IncorrectOperationException("Not invoked on a conditional");
    }
    GrExpression thenBranch = condExp.getThenBranch();
    GrExpression elseBranch = condExp.getElseBranch();

    Object thenVal = GroovyConstantExpressionEvaluator.evaluate(thenBranch);
    if (Boolean.TRUE.equals(thenVal) && elseBranch != null) {
      // aaa ? true : bbb -> aaa || bbb
      GrExpression conditionExp = condExp.getCondition();

      String conditionExpText = getStringToPutIntoOrExpression(conditionExp);
      String elseExpText = getStringToPutIntoOrExpression(elseBranch);
      String newExp = conditionExpText + "||" + elseExpText;
      manageReplace(updater, condExp, conditionExpText, newExp);
      return;
    }

    Object elseVal = GroovyConstantExpressionEvaluator.evaluate(elseBranch);
    if (Boolean.FALSE.equals(elseVal) && thenBranch != null) {
      // aaa ? bbb : false -> aaa && bbb
      GrExpression conditionExp = condExp.getCondition();

      String conditionExpText = getStringToPutIntoAndExpression(conditionExp);
      String thenExpText = getStringToPutIntoAndExpression(thenBranch);


      String newExp = conditionExpText + "&&" + thenExpText;
      manageReplace(updater, condExp, conditionExpText, newExp);
    }
  }

  private static void manageReplace(ModPsiUpdater updater,
                                    GrConditionalExpression condExp,
                                    String conditionExpText, String newExp) {
    int caretOffset = conditionExpText.length() + 2; // after operation sign

    GrExpression expressionFromText = GroovyPsiElementFactory.getInstance(condExp.getProject())
      .createExpressionFromText(newExp, condExp .getContext());

    expressionFromText = (GrExpression)condExp.replace(expressionFromText);

    updater.moveTo(expressionFromText.getTextOffset() + caretOffset); // just past operation sign
  }

  /**
   * Convert an expression into something which can be put inside ( a && b )
   * Wrap in parenthesis, if necessary
   *
   * @return a string representing the expression
   */
  @NotNull
  private static String getStringToPutIntoAndExpression(GrExpression expression) {
    String expressionText = expression.getText();
    if (ParenthesesUtils.AND_PRECEDENCE < ParenthesesUtils.getPrecedence(expression)) {
      expressionText = "(" + expressionText + ")";
    }
    return expressionText;
  }

  @NotNull
  private static String getStringToPutIntoOrExpression(GrExpression expression) {
    String expressionText = expression.getText();
    if (ParenthesesUtils.OR_PRECEDENCE < ParenthesesUtils.getPrecedence(expression)) {
      expressionText = "(" + expressionText + ")";
    }
    return expressionText;
  }

  @NotNull
  @Override
  protected PsiElementPredicate getElementPredicate() {
    return element -> {
      if (!(element instanceof GrConditionalExpression condExp)) {
        return false;
      }

      PsiType condType = condExp.getType();
      if (condType == null || !PsiTypes.booleanType().isConvertibleFrom(condType)) {
        return false;
      }

      GrExpression thenBranch = condExp.getThenBranch();
      GrExpression elseBranch = condExp.getElseBranch();

      Object thenVal = GroovyConstantExpressionEvaluator.evaluate(thenBranch);
      if (Boolean.TRUE.equals(thenVal) && elseBranch != null) {
        return true;
      }

      Object elseVal = GroovyConstantExpressionEvaluator.evaluate(elseBranch);
      if (thenBranch != null && Boolean.FALSE.equals(elseVal)) {
        return true;
      }

      return false;
    };
  }
}
