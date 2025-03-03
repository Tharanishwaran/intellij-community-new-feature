// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.siyeh.ig.abstraction;

import com.intellij.codeInsight.daemon.impl.UnusedSymbolUtil;
import com.intellij.codeInsight.daemon.impl.analysis.JavaHighlightUtil;
import com.intellij.codeInsight.intention.LowPriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInsight.options.JavaClassValidator;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.UpdateInspectionOptionFix;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.modcommand.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.searches.OverridingMethodsSearch;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.Query;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.OrderedSet;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.psiutils.ClassUtils;
import com.siyeh.ig.psiutils.CommentTracker;
import com.siyeh.ig.psiutils.MethodUtils;
import com.siyeh.ig.psiutils.WeakestTypeFinder;
import org.jdom.Content;
import org.jdom.Element;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static com.intellij.codeInspection.options.OptPane.*;

public class TypeMayBeWeakenedInspection extends BaseInspection {
  @SuppressWarnings({"PublicField", "WeakerAccess", "unused"})
  public boolean useRighthandTypeAsWeakestTypeInAssignments = true;

  @SuppressWarnings({"PublicField", "WeakerAccess", "unused"})
  public boolean useParameterizedTypeForCollectionMethods = true;

  @SuppressWarnings({"PublicField", "WeakerAccess", "unused"})
  public boolean doNotWeakenToJavaLangObject = true;

  @SuppressWarnings("PublicField")
  public boolean onlyWeakentoInterface = true;

  @SuppressWarnings({"PublicField", "unused"})
  public boolean doNotWeakenReturnType = true;

  @SuppressWarnings({"PublicField", "WeakerAccess"})
  public boolean doNotWeakenInferredVariableType;

  public OrderedSet<String> myStopClassSet = new OrderedSet<>();

  @Override
  protected @NotNull String buildErrorString(Object... infos) {
    final PsiElement element = (PsiElement)infos[0];
    @SuppressWarnings("unchecked") final Collection<PsiClass> weakerClasses = (Collection<PsiClass>)infos[1];
    @NonNls final StringBuilder builder = new StringBuilder();
    final Iterator<PsiClass> iterator = weakerClasses.iterator();
    if (iterator.hasNext()) {
      builder.append('\'').append(getClassName(iterator.next())).append('\'');
      while (iterator.hasNext()) {
        builder.append(", '").append(getClassName(iterator.next())).append('\'');
      }
    }
    if (element instanceof PsiField) {
      return InspectionGadgetsBundle.message("inspection.type.may.be.weakened.field.problem.descriptor",
                                             builder.toString());
    }
    if (element instanceof PsiParameter) {
      return InspectionGadgetsBundle.message("inspection.type.may.be.weakened.parameter.problem.descriptor",
                                             builder.toString());
    }
    if (element instanceof PsiMethod) {
      return InspectionGadgetsBundle.message("inspection.type.may.be.weakened.method.problem.descriptor",
                                             builder.toString());
    }
    return InspectionGadgetsBundle.message("inspection.type.may.be.weakened.problem.descriptor", builder.toString());
  }

  @Override
  protected LocalQuickFix @NotNull [] buildFixes(Object... infos) {
    final PsiElement element = (PsiElement)infos[0];
    @SuppressWarnings("unchecked") final Collection<PsiClass> weakerClasses = (Collection<PsiClass>)infos[1];
    final PsiClass originalClass = (PsiClass)infos[2];
    final boolean onTheFly = (boolean)infos[3];
    final List<LocalQuickFix> fixes = new SmartList<>();

    if (element instanceof PsiVariable && !doNotWeakenInferredVariableType) {
      PsiTypeElement typeElement = ((PsiVariable)element).getTypeElement();
      if (typeElement != null && typeElement.isInferredType()) {
        final String optionText = InspectionGadgetsBundle.message("inspection.type.may.be.weakened.do.not.weaken.inferred.variable.type");
        fixes.add(LocalQuickFix.from(new UpdateInspectionOptionFix(this, "doNotWeakenInferredVariableType", optionText, true)));
      }
    }
    for (PsiClass weakestClass : weakerClasses) {
      final String className = getClassName(weakestClass);
      if (className == null) {
        continue;
      }
      fixes.add(new TypeMayBeWeakenedFix(className));
      List<String> candidates = getInheritors(originalClass, weakestClass);
      candidates.removeAll(myStopClassSet);
      if (!candidates.isEmpty() && (onTheFly || candidates.size() == 1)) {
        fixes.add(new AddStopWordQuickfix(candidates)); // not this class name, but all superclass names excluding this
      }
    }
    return fixes.toArray(LocalQuickFix.EMPTY_ARRAY);
  }

  @NotNull
  private static List<String> getInheritors(@NotNull PsiClass from, @NotNull PsiClass to) {
    List<String> candidates = new ArrayList<>();
    String fromName = getClassName(from);
    if (fromName != null) {
      candidates.add(fromName);
    }
    for (PsiClass cls : InheritanceUtil.getSuperClasses(from)) {
      if (cls.isInheritor(to, true)) {
        String name = getClassName(cls);
        if (name == null) continue;
        candidates.add(name);
      }
    }
    return candidates;
  }

  class AddStopWordQuickfix extends ModCommandQuickFix implements LowPriorityAction, LocalQuickFix {
    private final List<String> myCandidates;

    AddStopWordQuickfix(@NotNull List<String> candidates) {
      myCandidates = candidates;
    }

    @Nls
    @NotNull
    @Override
    public String getName() {
      if (myCandidates.size() == 1) {
        return InspectionGadgetsBundle.message("inspection.type.may.be.weakened.add.stopper.single", myCandidates.get(0));
      }
      return InspectionGadgetsBundle.message("inspection.type.may.be.weakened.add.stopper");
    }

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return InspectionGadgetsBundle.message("inspection.type.may.be.weakened.add.stop.class.family");
    }

    @Override
    public @NotNull ModCommand perform(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      return new ModChooseAction(InspectionGadgetsBundle.message("inspection.type.may.be.weakened.add.stop.class.selection.popup"),
                                 ContainerUtil.map(myCandidates, DoAddStopAction::new));
    }
    
    @Override
    public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull ProblemDescriptor previewDescriptor) {
      return new IntentionPreviewInfo.Html(
        InspectionGadgetsBundle.message("inspection.type.may.be.weakened.add.stopper.preview")
      );
    }

    private class DoAddStopAction implements ModCommandAction {
      private final @NlsSafe String myCandidate;

      DoAddStopAction(String candidate) {
        myCandidate = candidate;
      }

      @Override
      public @NotNull String getFamilyName() {
        return myCandidate;
      }

      @Override
      public @Nullable Presentation getPresentation(@NotNull ActionContext context) {
        return Presentation.of(myCandidate);
      }

      @Override
      public @NotNull ModCommand perform(@NotNull ActionContext context) {
        return ModCommand.updateInspectionOption(context.file(), TypeMayBeWeakenedInspection.this,
                                       insp -> insp.myStopClassSet.add(myCandidate));
      }
    }
  }

  @Override
  public void readSettings(@NotNull Element node) {
    super.readSettings(node);
    readStopClasses(node);
  }

  private void readStopClasses(@NotNull Element node) {
    List<Element> classes = node.getChildren("stopClasses");
    if (classes.isEmpty()) return;
    Element element = classes.get(0);
    List<Content> contentList = element.getContent();
    if (contentList.isEmpty()) return;
    String text = contentList.get(0).getValue();
    myStopClassSet.addAll(Arrays.asList(text.split(",")));
  }

  @Override
  public void writeSettings(@NotNull Element node) {
    defaultWriteSettings(node, "doNotWeakenReturnType", "doNotWeakenInferredVariableType", "stopClasses");
    writeBooleanOption(node, "doNotWeakenReturnType", true);
    writeBooleanOption(node, "doNotWeakenInferredVariableType", false);
    if (!myStopClassSet.isEmpty()) {
      Element stopClasses = new Element("stopClasses");
      stopClasses.addContent(String.join(",", myStopClassSet));
      node.addContent(stopClasses);
    }
  }

  private static String getClassName(@NotNull PsiClass aClass) {
    final String qualifiedName = aClass.getQualifiedName();
    return qualifiedName == null ? aClass.getName() : qualifiedName;
  }

  @Override
  public @NotNull OptPane getOptionsPane() {
    return pane(
      checkbox("onlyWeakentoInterface", InspectionGadgetsBundle.message("inspection.type.may.be.weakened.only.weaken.to.an.interface")),
      checkbox("doNotWeakenInferredVariableType",
               InspectionGadgetsBundle.message("inspection.type.may.be.weakened.do.not.weaken.inferred.variable.type")),
      stringList("myStopClassSet", InspectionGadgetsBundle.message("inspection.type.may.be.weakened.add.stop.class.selection.table.label"),
                 new JavaClassValidator().withTitle(InspectionGadgetsBundle.message("inspection.type.may.be.weakened.add.stop.class.selection.table"))));
  }

  private static class TypeMayBeWeakenedFix extends PsiUpdateModCommandQuickFix {
    private final String fqClassName;

    TypeMayBeWeakenedFix(@NotNull String fqClassName) {
      this.fqClassName = fqClassName;
    }

    @Override
    @NotNull
    public String getName() {
      return InspectionGadgetsBundle.message("inspection.type.may.be.weakened.quickfix", fqClassName);
    }

    @NotNull
    @Override
    public String getFamilyName() {
      return InspectionGadgetsBundle.message("inspection.type.may.be.weakened.weaken.type.family");
    }

    @Override
    protected void applyFix(@NotNull Project project, @NotNull PsiElement element, @NotNull ModPsiUpdater updater) {
      final PsiElement parent = element.getParent();
      final PsiTypeElement typeElement;
      if (parent instanceof PsiVariable variable) {
        typeElement = variable.getTypeElement();
      }
      else if (parent instanceof PsiMethod method) {
        typeElement = method.getReturnTypeElement();
      }
      else {
        return;
      }
      if (typeElement == null) {
        return;
      }
      final PsiJavaCodeReferenceElement componentReferenceElement = typeElement.getInnermostComponentReferenceElement();
      boolean isInferredType = typeElement.isInferredType();
      if (componentReferenceElement == null && !isInferredType) {
        return;
      }
      final PsiType oldType = typeElement.getType();
      if (!(oldType instanceof PsiClassType oldClassType)) {
        return;
      }
      final JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
      final PsiElementFactory factory = facade.getElementFactory();
      final PsiType type = factory.createTypeFromText(fqClassName, element);
      if (!(type instanceof PsiClassType classType)) {
        return;
      }
      final PsiClass aClass = classType.resolve();
      if (aClass != null) {
        final PsiTypeParameter[] typeParameters = aClass.getTypeParameters();
        if (typeParameters.length != 0) {
          PsiClass newClass = classType.resolve();
          if (newClass == null) return;
          final Map<PsiTypeParameter, PsiType> typeParameterMap = new HashMap<>();
          for (int i = 0; i < typeParameters.length; i++) {
            final PsiTypeParameter typeParameter = typeParameters[i];
            final PsiType parameterType = PsiUtil.substituteTypeParameter(oldClassType, newClass, i, false);
            typeParameterMap.put(typeParameter, parameterType);
          }
          final PsiSubstitutor substitutor = factory.createSubstitutor(typeParameterMap);
          classType = factory.createType(aClass, substitutor);
        }
      }
      final PsiElement replacement;
      if (isInferredType) {
        PsiTypeElement newTypeElement = factory.createTypeElement(classType);
        replacement = new CommentTracker().replaceAndRestoreComments(typeElement, newTypeElement);
      }
      else {
        final PsiJavaCodeReferenceElement referenceElement = factory.createReferenceElementByType(classType);
        replacement = new CommentTracker().replaceAndRestoreComments(componentReferenceElement, referenceElement);
      }
      final JavaCodeStyleManager javaCodeStyleManager = JavaCodeStyleManager.getInstance(project);
      javaCodeStyleManager.shortenClassReferences(replacement);
    }
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new TypeMayBeWeakenedVisitor();
  }

  @NotNull
  private static PsiClass tryReplaceWithParentStopper(@NotNull PsiClass fromIncl,
                                                      @NotNull PsiClass toIncl,
                                                      @NotNull Collection<String> stopClasses) {
    for (PsiClass superClass : InheritanceUtil.getSuperClasses(fromIncl)) {
      if (!superClass.isInheritor(toIncl, true)) continue;
      if (stopClasses.contains(getClassName(superClass))) {
        return superClass;
      }
    }
    return toIncl;
  }

  private class TypeMayBeWeakenedVisitor extends BaseInspectionVisitor {

    @Override
    public void visitVariable(@NotNull PsiVariable variable) {
      super.visitVariable(variable);
      if (variable instanceof PsiParameter parameter) {
        if (parameter instanceof PsiPatternVariable) return;
        final PsiElement declarationScope = parameter.getDeclarationScope();
        if (declarationScope instanceof PsiCatchSection) {
          // do not weaken catch block parameters
          return;
        }
        if (declarationScope instanceof PsiLambdaExpression && parameter.getTypeElement() == null) {
          //no need to check inferred lambda params
          return;
        }
        if (declarationScope instanceof PsiMethod method) {
          final PsiClass containingClass = method.getContainingClass();
          if (containingClass == null || containingClass.isInterface()) {
            return;
          }
          if (JavaHighlightUtil.isSerializationRelatedMethod(method, containingClass)) {
            return;
          }
          if (MethodUtils.hasSuper(method)) {
            // do not try to weaken parameters of methods with
            // super methods
            return;
          }
          final Query<PsiMethod> overridingSearch = OverridingMethodsSearch.search(method);
          if (overridingSearch.findFirst() != null) {
            // do not try to weaken parameters of methods with
            // overriding methods.
            return;
          }
        }
      }
      if (isOnTheFly() && variable instanceof PsiField) {
        // checking variables with greater visibility is too expensive
        // for error checking in the editor
        if (!variable.hasModifierProperty(PsiModifier.PRIVATE) || variable instanceof PsiEnumConstant) {
          return;
        }
      }
      if (doNotWeakenInferredVariableType) {
        PsiTypeElement typeElement = variable.getTypeElement();
        if (typeElement != null && typeElement.isInferredType()) {
          return;
        }
      }
      if (variable instanceof PsiParameter) {
        final PsiElement parent = variable.getParent();
        if (parent instanceof PsiForeachStatement foreachStatement) {
          final PsiExpression iteratedValue = foreachStatement.getIteratedValue();
          if (!(iteratedValue instanceof PsiNewExpression) && !(iteratedValue instanceof PsiTypeCastExpression)) {
            return;
          }
        }
      }
      else {
        final PsiExpression initializer = variable.getInitializer();
        if (!(initializer instanceof PsiNewExpression) && !(initializer instanceof PsiTypeCastExpression)) {
          return;
        }
      }
      if (variable instanceof PsiParameter) {
        PsiMethod method = PsiTreeUtil.getParentOfType(variable, PsiMethod.class);
        if (method == null || UnusedSymbolUtil.isImplicitUsage(variable.getProject(), method)) return;
      }
      if (UnusedSymbolUtil.isImplicitWrite(variable) || UnusedSymbolUtil.isImplicitRead(variable)) {
        return;
      }
      PsiClassType classType = ObjectUtils.tryCast(variable.getType(), PsiClassType.class);
      if (classType == null) return;
      PsiClass originClass = classType.resolve();
      if (originClass == null) return;
      if (myStopClassSet.contains(getClassName(originClass))) return;
      Collection<PsiClass> weakestClasses = computeWeakestClasses(variable, originClass);
      if (weakestClasses.isEmpty()) {
        return;
      }
      registerVariableError(variable, variable, weakestClasses, originClass, isOnTheFly());
    }

    @NotNull
    private Collection<PsiClass> computeWeakestClasses(@NotNull PsiElement element, @NotNull PsiClass originClass) {
      Collection<PsiClass> weakestClasses =
        WeakestTypeFinder.calculateWeakestClassesNecessary(element, true);
      weakestClasses.remove(ClassUtils.findObjectClass(element));
      if (onlyWeakentoInterface) {
        weakestClasses.removeIf(weakestClass -> !weakestClass.isInterface());
      }

      weakestClasses = ContainerUtil.map(weakestClasses, psiClass -> tryReplaceWithParentStopper(originClass, psiClass, myStopClassSet));
      return weakestClasses;
    }
  }
}