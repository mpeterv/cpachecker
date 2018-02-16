/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2014  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.cfa.postprocessing.function;

import static com.google.common.collect.FluentIterable.from;
import static org.sosy_lab.cpachecker.util.CFAUtils.leavingEdges;

import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.MutableCFA;
import org.sosy_lab.cpachecker.cfa.ast.ADeclaration;
import org.sosy_lab.cpachecker.cfa.ast.AStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CFieldReference;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionCall;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionCallAssignmentStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionCallExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionCallStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CIdExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CParameterDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CPointerExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CSimpleDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.model.c.CFunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.model.c.CStatementEdge;
import org.sosy_lab.cpachecker.cfa.types.MachineModel;
import org.sosy_lab.cpachecker.cfa.types.c.CBasicType;
import org.sosy_lab.cpachecker.cfa.types.c.CFunctionType;
import org.sosy_lab.cpachecker.cfa.types.c.CFunctionTypeWithNames;
import org.sosy_lab.cpachecker.cfa.types.c.CPointerType;
import org.sosy_lab.cpachecker.cfa.types.c.CSimpleType;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.cfa.types.c.CVoidType;
import org.sosy_lab.cpachecker.util.CFATraversal;
import org.sosy_lab.cpachecker.util.Pair;


/**
 * This class is responsible for replacing calls via function pointers like (*fp)()
 * with code similar to the following:
 * if (fp == &f)
 *   f();
 * else if (fp == &g)
 *   f();
 * else
 *   (*fp)();
 *
 * The set of candidate functions used is configurable.
 * No actual call edges to the other functions are introduced.
 * The inserted function call statements look just like regular functions call statements.
 * The edge in the "else" branch is optional and configurable.
 */

@Options
public class CFunctionPointerResolver {

  @Option(secure=true, name="analysis.matchAssignedFunctionPointers",
      description="Use as targets for call edges only those shich are assigned to the particular expression (structure field).")
  private boolean matchAssignedFunctionPointers = false;

  @Option(secure=true, name="analysis.replaseFunctionWithParametrPointer",
      description="Use if you are going to change function with function pionter parametr")
  private boolean replaseFunctionWithParametrPointer = false;

  private enum FunctionSet {
    // The items here need to be declared in the order they should be used when checking function.
    ALL, //all defined functions considered (Warning: some CPAs require at least EQ_PARAM_SIZES)
    USED_IN_CODE, //includes only functions which address is taken in the code
    EQ_PARAM_COUNT, //all functions with matching number of parameters considered
    EQ_PARAM_SIZES, //all functions with parameters with matching sizes
    EQ_PARAM_TYPES, //all functions with matching number and types of parameters considered
    RETURN_VALUE,   //void functions are not considered for assignments
  }

  @Option(
    secure = true,
    name = "analysis.functionPointerTargets",
    description = "potential targets for call edges created for function pointer calls"
  )
  private Set<FunctionSet> functionSets =
      ImmutableSet.of(
          FunctionSet.USED_IN_CODE, FunctionSet.RETURN_VALUE, FunctionSet.EQ_PARAM_TYPES);

  @Option(
      secure = true,
      name = "analysis.functionPointerParameterTargets",
      description = "potential targets for call edges created for function pointer parameter calls"
    )
    private Set<FunctionSet> functionParameterSets =
        ImmutableSet.of(
            FunctionSet.USED_IN_CODE, FunctionSet.RETURN_VALUE, FunctionSet.EQ_PARAM_TYPES);

  private static class TargetFunctions {
    private Collection<FunctionEntryNode> candidateFunctions;
    private BiPredicate<Object, CFunctionType> matchingFunctionCall;
    private ImmutableSetMultimap<String, String> candidateFunctionsForField;
    private ImmutableSetMultimap<String, String> globalsMatching;
  }

  TargetFunctions targetFunctions;
  TargetFunctions targetParamFunctions;

  private final MutableCFA cfa;
  private final LogManager logger;
  private Configuration pConfig;

  private TargetFunctions createMatchingFunctions(MutableCFA pCfa, List<Pair<ADeclaration, String>> pGlobalVars,
      Collection<FunctionSet> functionSets) {

    TargetFunctions targetFunctions = new TargetFunctions();

    if (functionSets.contains(FunctionSet.USED_IN_CODE)) {
      CReferencedFunctionsCollector varCollector;
      if (matchAssignedFunctionPointers) {
        varCollector = new CReferencedFunctionsCollectorWithFieldsMatching();
      } else {
        varCollector = new CReferencedFunctionsCollector();
      }
      for (CFANode node : cfa.getAllNodes()) {
        for (CFAEdge edge : leavingEdges(node)) {
          varCollector.visitEdge(edge);
        }
      }
      for (Pair<ADeclaration, String> decl : pGlobalVars) {
        if (decl.getFirst() instanceof CVariableDeclaration) {
          CVariableDeclaration varDecl = (CVariableDeclaration)decl.getFirst();
          varCollector.visitDeclaration(varDecl);
        }
      }
      Set<String> addressedFunctions = varCollector.getCollectedFunctions();
      targetFunctions.candidateFunctions =
          from(Sets.intersection(addressedFunctions, cfa.getAllFunctionNames()))
              .transform(Functions.forMap(cfa.getAllFunctions()))
              .toList();

      if (matchAssignedFunctionPointers) {
        targetFunctions.candidateFunctionsForField =
            ImmutableSetMultimap.copyOf(
                ((CReferencedFunctionsCollectorWithFieldsMatching) varCollector)
                    .getFieldMatching());

        targetFunctions.globalsMatching = ImmutableSetMultimap.copyOf(
            ((CReferencedFunctionsCollectorWithFieldsMatching) varCollector).getGlobalMatching());
      } else {
        targetFunctions.candidateFunctionsForField = null;
        targetFunctions.globalsMatching = null;
      }

      if (logger.wouldBeLogged(Level.ALL)) {
        logger.log(Level.ALL, "Possible target functions of function pointers:\n",
            Joiner.on('\n').join(targetFunctions.candidateFunctions));
      }

    } else {
      targetFunctions.candidateFunctions = cfa.getAllFunctionHeads();
      targetFunctions.candidateFunctionsForField = null;
      targetFunctions.globalsMatching = null;
    }

    targetFunctions.matchingFunctionCall = getFunctionSetPredicate(functionSets);

    return targetFunctions;
  }

  public CFunctionPointerResolver(MutableCFA pCfa, List<Pair<ADeclaration, String>> pGlobalVars,
      Configuration config, LogManager pLogger) throws InvalidConfigurationException {
    cfa = pCfa;
    logger = pLogger;
    pConfig = config;

    config.inject(this);

    targetFunctions = createMatchingFunctions(pCfa, pGlobalVars, functionSets);
    targetParamFunctions = createMatchingFunctions(pCfa, pGlobalVars, functionParameterSets);
  }

  private BiPredicate<Object, CFunctionType> getFunctionSetPredicate(
      Collection<FunctionSet> pFunctionSets) {
    List<BiPredicate<Object, CFunctionType>> predicates = new ArrayList<>();

    // note that this set is sorted according to the declaration order of the enum
    EnumSet<FunctionSet> functionSets = EnumSet.copyOf(pFunctionSets);

    if (functionSets.contains(FunctionSet.EQ_PARAM_TYPES)
        || functionSets.contains(FunctionSet.EQ_PARAM_SIZES)) {
      functionSets.add(FunctionSet.EQ_PARAM_COUNT); // TYPES and SIZES need COUNT checked first
    }

    for (FunctionSet functionSet : functionSets) {
      switch (functionSet) {
      case ALL:
        // do nothing
        break;
        case EQ_PARAM_COUNT:
          predicates.add(this::checkParamCount);
          break;
        case EQ_PARAM_SIZES:
          predicates.add(this::checkReturnAndParamSizes);
          break;
        case EQ_PARAM_TYPES:
          predicates.add(this::checkReturnAndParamTypes);
          break;
        case RETURN_VALUE:
          predicates.add(this::checkReturnValue);
          break;
      case USED_IN_CODE:
        // Not necessary, only matching functions are in the
        // candidateFunctions set
        break;
      default:
        throw new AssertionError();
      }
    }
    return predicates.stream().reduce((a, b) -> true, BiPredicate::and);
  }

  /**
   * This method traverses the whole CFA,
   * potentially replacing function pointer calls with regular function calls.
   */
  public void resolveFunctionPointers() throws InvalidConfigurationException{

    // 1.Step: get all function calls
    final FunctionPointerCallCollector visitor = new FunctionPointerCallCollector();
    for (FunctionEntryNode functionStartNode : cfa.getAllFunctionHeads()) {
      CFATraversal.dfs().traverseOnce(functionStartNode, visitor);
    }

    // 2.Step: replace functionCalls with functioncall- and return-edges
    // This loop replaces function pointer calls inside the given function with regular function calls.

    final EdgeReplacerFunctionPointer edgeReplacerFunctionPointer = new EdgeReplacerFunctionPointer(cfa, pConfig, logger);
    for (final CStatementEdge edge : visitor.functionPointerCalls) {
      Collection<CFunctionEntryNode> funcs = getTargets(edge.getStatement(), edge, targetFunctions);
      edgeReplacerFunctionPointer.instrument(edge, funcs, null, CreateEdgeFlags.CREATE_SUMMARY_EDGE);
    }

    if (replaseFunctionWithParametrPointer) {
      final FunctionWithParamPointerCallCollector visitorParams = new FunctionWithParamPointerCallCollector();
      for (FunctionEntryNode functionStartNode : cfa.getAllFunctionHeads()) {
        CFATraversal.dfs().traverseOnce(functionStartNode, visitorParams);
      }

      EdgeReplacerParameterFunctionPointer edgeReplacerParameterFunctionPointer = new EdgeReplacerParameterFunctionPointer(cfa, pConfig, logger);
      for (final CStatementEdge edge : visitorParams.functionPointerCalls) {
        CExpression param = functionArgumentPointerCall((CFunctionCall) edge.getStatement());
        Collection<CFunctionEntryNode> funcs = getTargets(param, edge, targetParamFunctions);
        edgeReplacerParameterFunctionPointer.instrument(edge, funcs, param, CreateEdgeFlags.DONT_CREATE_SUMMARY_EDGE);
      }
    }
  }

  private CExpression functionArgumentPointerCall(CFunctionCall call) {
    for (CExpression param : call.getFunctionCallExpression().getParameterExpressions()) {
      if (param.getExpressionType() instanceof CPointerType
          && ((CPointerType) param.getExpressionType()).getType() instanceof CFunctionTypeWithNames
          && ((param instanceof CIdExpression && ((CIdExpression) param).getDeclaration().getType() instanceof CPointerType)
          || (param instanceof CFieldReference))) {
        return param;
      }
    }
    return null;
  }

  private boolean isFunctionPointerCall(CFunctionCall call) {
    CFunctionCallExpression callExpr = call.getFunctionCallExpression();
    if (callExpr.getDeclaration() != null) {
      // "f()" where "f" is a declared function
      return false;
    }

    CExpression nameExpr = callExpr.getFunctionNameExpression();
    if (nameExpr instanceof CIdExpression
        && ((CIdExpression)nameExpr).getDeclaration() == null) {
      // "f()" where "f" is an undefined identifier
      // Someone calls an undeclared function.
      return false;
    }

    // Either "exp()" where "exp" is a more complicated expression,
    // or "f()" where "f" is a variable.
    return true;
  }

  private Collection<CFunctionEntryNode> getTargets(Object o, CStatementEdge statement, TargetFunctions targetFunctions) {
    CExpression nameExp = null;
    Collection<CFunctionEntryNode> funcs = null;

    if (o instanceof CFunctionCall) {
      CFunctionCall functionCall = (CFunctionCall) o;
      CFunctionCallExpression fExp = functionCall.getFunctionCallExpression();
      logger.log(Level.FINEST, "Function pointer call", fExp);
      nameExp = fExp.getFunctionNameExpression();
      funcs = getFunctionSet(functionCall, targetFunctions);
    } else if (o instanceof CExpression) {
      nameExp = (CExpression) o;
      logger.log(Level.FINEST, "Param", nameExp);
      funcs = getFunctionSet(nameExp, targetFunctions);
    } else {
      throw new AssertionError("Unknown parameter");
    }

    if (matchAssignedFunctionPointers) {
      CExpression expression = nameExp;
      if (expression instanceof CPointerExpression) {
        expression = ((CPointerExpression) expression).getOperand();
      }
      final Set<String> matchedFuncs;
      if( expression instanceof CFieldReference) {
        String fieldName = ((CFieldReference)expression).getFieldName();
        matchedFuncs = targetFunctions.candidateFunctionsForField.get(fieldName);

      } else if (expression instanceof CIdExpression) {
        String variableName = ((CIdExpression)expression).getName();
         matchedFuncs = targetFunctions.globalsMatching.get(variableName);
      } else {
        matchedFuncs = Collections.emptySet();
      }
      if (matchedFuncs.isEmpty()) {
        CSimpleDeclaration decl = ((CIdExpression) expression).getDeclaration();
        if (decl == null) {
          funcs = Collections.emptySet();
        } else if (decl instanceof CDeclaration && ((CDeclaration) decl).isGlobal()) {
          //TODO means, that our heuristics missed something
          funcs = Collections.emptySet();
        }
      } else {
        funcs = from(funcs).
            filter(f -> matchedFuncs.contains(f.getFunctionName())).
            toSet();
      }
    }

    if (funcs.isEmpty()) {
      // no possible targets, we leave the CFA unchanged and print a warning
      logger.logf(Level.WARNING, "%s: Function pointer %s with type %s is called,"
          + " but no possible target functions were found.",
          statement.getFileLocation(), nameExp.toASTString(), nameExp.getExpressionType().toASTString("*"));
    } else {
      logger.log(
          Level.FINEST,
          "Inserting edges for the function pointer",
          nameExp.toASTString(),
          "with type",
          nameExp.getExpressionType().toASTString("*"),
          "to the functions",
          from(funcs).transform(CFunctionEntryNode::getFunctionName));
    }

    return funcs;
  }

  private List<CFunctionEntryNode> getFunctionSet(Object o, TargetFunctions targetFunctions) {
    return from(targetFunctions.candidateFunctions)
        .filter(CFunctionEntryNode.class)
        .filter(f -> targetFunctions.matchingFunctionCall.test(o, f.getFunctionDefinition().getType()))
        .toList();
  }

  private boolean checkReturnAndParamSizes(Object o, CFunctionType functionType) {
    CType actRet = null;
    String name = null;
    List<CExpression> exprParams = null;

    if (o instanceof CFunctionCall) {
      final CFunctionCallExpression functionCallExpression = ((CFunctionCall) o).getFunctionCallExpression();
      exprParams = functionCallExpression.getParameterExpressions();
      actRet = functionCallExpression.getExpressionType();
      name = functionCallExpression.toASTString();
    } else if (o instanceof CExpression) {
      CExpression param = (CExpression) o;
      List<CParameterDeclaration> p = ((CFunctionTypeWithNames) (((CPointerType) param.getExpressionType()).getType())).getParameterDeclarations();
      exprParams = new ArrayList<CExpression>();
      for (CParameterDeclaration pp : p) {
        CIdExpression pp2 = new CIdExpression(param.getFileLocation(), pp.getType(), pp.getName(), null);
        exprParams.add(pp2);
      }
      actRet = param.getExpressionType();
      name = param.toASTString();
    } else {
      throw new AssertionError("Unknown parameter");
    }

    CType declRet = functionType.getReturnType();
    final MachineModel machine = cfa.getMachineModel();
    if (machine.getSizeof(declRet) != machine.getSizeof(actRet)) {
      logger.log(Level.FINEST, "Function call", name, "with type", actRet,
          "does not match function", functionType, "with return type", declRet,
          "because of return types with different sizes.");
      return false;
    }

    List<CType> declParams = functionType.getParameters();
    for (int i=0; i<declParams.size(); i++) {
      CType dt = declParams.get(i);
      CType et = exprParams.get(i).getExpressionType();
      if (machine.getSizeof(dt) != machine.getSizeof(et)) {
        logger.log(Level.FINEST, "Function call", name,
            "does not match function", functionType,
            "because actual parameter", i, "has type", et, "instead of", dt,
            "(differing sizes).");
        return false;
      }
    }

    return true;
  }

  private boolean checkReturnAndParamTypes(Object o, CFunctionType functionType) {
    CType actRet = null;
    String name = null;
    List<CExpression> exprParams = null;

    if (o instanceof CFunctionCall) {
      final CFunctionCallExpression functionCallExpression = ((CFunctionCall) o).getFunctionCallExpression();
      actRet = functionCallExpression.getExpressionType();
      exprParams = functionCallExpression.getParameterExpressions();
      name = functionCallExpression.toASTString();
    } else if (o instanceof CExpression) {
      CExpression param = (CExpression) o;
      List<CParameterDeclaration> p = ((CFunctionTypeWithNames) (((CPointerType) param.getExpressionType()).getType())).getParameterDeclarations();
      exprParams = new ArrayList<CExpression>();
      for (CParameterDeclaration pp : p) {
        CIdExpression pp2 = new CIdExpression(param.getFileLocation(), pp.getType(), pp.getName(), null);
        exprParams.add(pp2);
      }
      actRet = param.getExpressionType();
      name = param.toASTString();
    } else {
      throw new AssertionError("Unknown parameter");
    }

    CType declRet = functionType.getReturnType();
    if (!isCompatibleType(declRet, actRet)) {
      logger.log(Level.FINEST, "Function call", name, "with type", actRet,
          "does not match function", functionType, "with return type", declRet);
      return false;
    }

    List<CType> declParams = functionType.getParameters();
    for (int i=0; i<declParams.size(); i++) {
      CType dt = declParams.get(i);
      CType et = exprParams.get(i).getExpressionType();
      if (!isCompatibleType(dt, et)) {
        logger.log(Level.FINEST, "Function call", name,
            "does not match function", functionType,
            "because actual parameter", i, "has type", et, "instead of", dt);
        return false;
      }
    }
    return true;
  }

  /**
   * Exclude void functions if the return value of the function is used in an assignment.
   */
  private boolean checkReturnValue(Object o, CFunctionType functionType) {
    CFunctionCall functionCall = null;

    if (o instanceof CFunctionCall) {
      functionCall = (CFunctionCall) o;
    } else if (o instanceof CExpression) {
      CExpression param = (CExpression) o;
      List<CParameterDeclaration> p = ((CFunctionTypeWithNames) (((CPointerType) param.getExpressionType()).getType())).getParameterDeclarations();
      List<CExpression> params2 = new ArrayList<CExpression>();
      for (CParameterDeclaration pp : p) {
        CIdExpression pp2 = new CIdExpression(param.getFileLocation(), pp.getType(), pp.getName(), null);
        params2.add(pp2);
      }
      CFunctionCallExpression cexpr = new CFunctionCallExpression(param.getFileLocation(), param.getExpressionType(), param, ImmutableList.copyOf(params2), null);
      functionCall = new CFunctionCallStatement(cexpr.getFileLocation(), cexpr);
    } else {
      throw new AssertionError("Unknown parameter");
    }

    if (functionCall instanceof CFunctionCallAssignmentStatement) {
      CType returnType = functionType.getReturnType().getCanonicalType();
      if (returnType instanceof CVoidType) {
        return false;
      }
    }
    return true;
  }

  /**
   * Check whether two types are assignment compatible.
   *
   * @param pDeclaredType The type that is declared (e.g., as variable type).
   * @param pActualType The type that is actually used (e.g., as type of an expression).
   * @return {@code true} if a value of actualType may be assigned to a variable of declaredType.
   */
  private boolean isCompatibleType(CType pDeclaredType, CType pActualType) {
    // Check canonical types
    CType declaredType = pDeclaredType.getCanonicalType();
    CType actualType = pActualType.getCanonicalType();

    // If types are equal, they are trivially compatible
    if (declaredType.equals(actualType)) {
      return true;
    }

    // Implicit conversions among basic types
    if (declaredType instanceof CSimpleType && actualType instanceof CSimpleType) {
      return true;
    }

    // Void pointer can be converted to any other pointer or integer
    if (declaredType instanceof CPointerType) {
      CPointerType declaredPointerType = (CPointerType) declaredType;
      if (declaredPointerType.getType() == CVoidType.VOID) {
        if (actualType instanceof CSimpleType) {
          CSimpleType actualSimpleType = (CSimpleType) actualType;
          CBasicType actualBasicType = actualSimpleType.getType();
          if (actualBasicType.isIntegerType()) {
            return true;
          }
        } else if (actualType instanceof CPointerType) {
          return true;
        }
      }
    }

    // Any pointer or integer can be converted to a void pointer
    if (actualType instanceof CPointerType) {
      CPointerType actualPointerType = (CPointerType) actualType;
      if (actualPointerType.getType() == CVoidType.VOID) {
        if (declaredType instanceof CSimpleType) {
          CSimpleType declaredSimpleType = (CSimpleType) declaredType;
          CBasicType declaredBasicType = declaredSimpleType.getType();
          if (declaredBasicType.isIntegerType()) {
            return true;
          }
        } else if (declaredType instanceof CPointerType) {
          return true;
        }
      }
    }

    // If both types are pointers, check if the inner types are compatible
    if (declaredType instanceof CPointerType && actualType instanceof CPointerType) {
      CPointerType declaredPointerType = (CPointerType) declaredType;
      CPointerType actualPointerType = (CPointerType) actualType;
      if (isCompatibleType(declaredPointerType.getType(), actualPointerType.getType())) {
        return true;
      }
    }

    return false;
  }

  private boolean checkParamCount(Object o, CFunctionType functionType) {
    List<CExpression> parameters = null;
    String name = null;
    if (o instanceof CFunctionCall) {
      CFunctionCall functionCall = (CFunctionCall) o;
      final CFunctionCallExpression functionCallExpression = functionCall.getFunctionCallExpression();
      //get the parameter expression
      parameters = functionCallExpression.getParameterExpressions();
      name = functionCallExpression.toASTString();
    } else if (o instanceof CExpression) {
      CExpression param = (CExpression) o;
      List<CParameterDeclaration> p = ((CFunctionTypeWithNames) (((CPointerType) param.getExpressionType()).getType())).getParameterDeclarations();
      parameters = new ArrayList<CExpression>();
      for (CParameterDeclaration pp : p) {
        CIdExpression pp2 = new CIdExpression(param.getFileLocation(), pp.getType(), pp.getName(), null);
        parameters.add(pp2);
      }
      name = param.toASTString();
    } else {
      throw new AssertionError("Unknown parameter");
    }

    int declaredParameters = functionType.getParameters().size();
    int actualParameters = parameters.size();

    if (actualParameters < declaredParameters) {
      logger.log(
          Level.FINEST,
          "Function call",
          name,
          "does not match function",
          functionType,
          "because there are not enough actual parameters.");
      return false;
    }

    if (!functionType.takesVarArgs() && actualParameters > declaredParameters) {
      logger.log(
          Level.FINEST,
          "Function call",
          name,
          "does not match function",
          functionType,
          "because there are too many actual parameters.");
      return false;
    }
    return true;
  }

  /** This Visitor collects all functioncalls for functionPointers.
   *  It should visit the CFA of each functions before creating super-edges (functioncall- and return-edges). */
  private static abstract class FunctionPointerCallCollectorCommon extends CFATraversal.DefaultCFAVisitor {
    final List<CStatementEdge> functionPointerCalls = new ArrayList<>();

    protected abstract boolean checkEdge(AStatement stmt);

    @Override
    public CFATraversal.TraversalProcess visitEdge(final CFAEdge pEdge) {
      if (pEdge instanceof CStatementEdge) {
        final CStatementEdge edge = (CStatementEdge) pEdge;
        final AStatement stmt = edge.getStatement();
        if (checkEdge(stmt)) {
          functionPointerCalls.add(edge);
        }
      }
      return CFATraversal.TraversalProcess.CONTINUE;
    }
  }

  private class FunctionPointerCallCollector extends FunctionPointerCallCollectorCommon {
    @Override
    protected boolean checkEdge(AStatement stmt) {
      if (stmt instanceof CFunctionCall && isFunctionPointerCall((CFunctionCall)stmt)) {
        return true;
      }
      return false;
    }
  }

  private class FunctionWithParamPointerCallCollector extends FunctionPointerCallCollectorCommon {
    @Override
    protected boolean checkEdge(AStatement stmt) {
      if (stmt instanceof CFunctionCall && !isFunctionPointerCall((CFunctionCall)stmt)
          && functionArgumentPointerCall((CFunctionCall)stmt) != null) {
        return true;
      }
      return false;
    }
  }
}
