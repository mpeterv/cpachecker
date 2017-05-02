/*
 * CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2017  Dirk Beyer
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
package org.sosy_lab.cpachecker.cpa.cpalias;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.cpachecker.cfa.ast.c.CDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpressionAssignmentStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionCallAssignmentStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionCallExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializer;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializerExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CParameterDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CDeclarationEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CFunctionCallEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CStatementEdge;
import org.sosy_lab.cpachecker.core.defaults.SingleEdgeTransferRelation;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.exceptions.CPATransferException;
import org.sosy_lab.cpachecker.exceptions.UnrecognizedCCodeException;
import org.sosy_lab.cpachecker.util.identifiers.AbstractIdentifier;
import org.sosy_lab.cpachecker.util.identifiers.IdentifierCreator;

//TODO is it possible to use ForwardingTransferRelation?
@Options(prefix = "cpa.alias")
public class AliasTransfer extends SingleEdgeTransferRelation {

  @Option(secure = true, name = "rcuAssign", description = "Name of a function responsible for "
      + "assignments to RCU pointers")
  private String assign = "rcu_assign_pointer";

  @Option(secure = true, name = "rcuDeref", description = "Name of a function responsible for "
      + "dereferences of RCU pointers")
  private String deref = "rcu_dereference";

  @Option(secure = true, name = "flowSense", description = "enable analysis flow sensitivity")
  private boolean flowSense = false;

  private final LogManager logger;

  public AliasTransfer(Configuration config, LogManager log) throws InvalidConfigurationException {
    config.inject(this);
    this.logger = log;
  }

  @Override
  public Collection<? extends AbstractState> getAbstractSuccessorsForEdge(
      AbstractState state, Precision precision, CFAEdge cfaEdge)
      throws CPATransferException, InterruptedException {

    IdentifierCreator ic = new IdentifierCreator();
    AliasState result = (AliasState) state;

    switch (cfaEdge.getEdgeType()) {
      case DeclarationEdge:
        logger.log(Level.ALL, "ALIAS: Declaration");
        CDeclaration cdecl = ((CDeclarationEdge) cfaEdge).getDeclaration();
        handleDeclaration(result, cdecl, ic, cfaEdge.getPredecessor().getFunctionName());
        break;
      case StatementEdge:
        logger.log(Level.ALL, "ALIAS: Statement");
        CStatement st = ((CStatementEdge) cfaEdge).getStatement();
        handleStatement(result, st, ic, cfaEdge.getPredecessor().getFunctionName());
        break;
      case FunctionCallEdge:
        logger.log(Level.ALL, "ALIAS: FunctionCall");
        CFunctionCallExpression fce = ((CFunctionCallEdge) cfaEdge).getSummaryEdge()
            .getExpression().getFunctionCallExpression();
        handleFunctionCall(result, fce, ic, cfaEdge.getPredecessor().getFunctionName());
        break;
      case AssumeEdge:
      case CallToReturnEdge:
      case FunctionReturnEdge:
      case ReturnStatementEdge:
      case BlankEdge:
        break;
      default:
        throw new UnrecognizedCCodeException("Unrecognized CFA edge.", cfaEdge);
    }

    return Collections.singleton(result);
  }

  private void handleStatement(AliasState pResult, CStatement pSt, IdentifierCreator ic, String functionName) {
    if (pSt != null) {
      logger.log(Level.ALL, "ALIAS: OK statement");
      ic.clear(functionName);
      if (pSt instanceof CExpressionAssignmentStatement) {
        CExpressionAssignmentStatement as = (CExpressionAssignmentStatement) pSt;
        AbstractIdentifier ail = as.getLeftHandSide().accept(ic);
        if (ail.isPointer()) {
          logger.log(Level.ALL, "ALIAS: Pointer in statement");
          ic.clearDereference();
          AbstractIdentifier air = as.getRightHandSide().accept(ic);
          if (flowSense) {
            pResult.clearAlias(ail);
          }
          pResult.addAlias(ail, air, logger);
        }
      } else if (pSt instanceof CFunctionCallAssignmentStatement) {
        CFunctionCallAssignmentStatement fca = (CFunctionCallAssignmentStatement) pSt;
        AbstractIdentifier ail = fca.getLeftHandSide().accept(ic);
        if (ail.isPointer()) {
          logger.log(Level.ALL, "ALIAS: Pointer in statement 2");
          handleFunctionCall(pResult, fca.getRightHandSide(), ic, functionName);
          ic.clearDereference();
          AbstractIdentifier fi =
              fca.getFunctionCallExpression().getFunctionNameExpression().accept(ic);
          if (flowSense) {
            pResult.clearAlias(ail);
          }
          pResult.addAlias(ail, fi, logger);
          // p = rcu_dereference(gp);
          if (fca.getRightHandSide().getDeclaration().getName().contains(deref)) {
            addToRCU(pResult, ail);
          }
        }
      }
    }
  }

  private void addToRCU(AliasState pResult, AbstractIdentifier pId) {
    logger.log(Level.ALL, "Contents of the state: " + pResult.getContents());
    AliasState.addToRCU(pResult, pId, logger);
  }


  private void handleFunctionCall(AliasState pResult, CFunctionCallExpression pRhs,
                                  IdentifierCreator ic, String functionName) {
    CFunctionDeclaration fd = pRhs.getDeclaration();
    List<CParameterDeclaration> formParams = fd.getParameters();
    List<CExpression> factParams = pRhs.getParameterExpressions();

    assert formParams.size() == factParams.size();

    AbstractIdentifier form;
    AbstractIdentifier fact;

    ic.clear(fd.getName());

    for (int i = 0; i < formParams.size(); ++i) {
      ic.clearDereference();
      form = handleDeclaration(pResult, formParams.get(i).asVariableDeclaration(), ic, fd.getName());
      if (form != null && form.isPointer()) {
        logger.log(Level.ALL, "ALIAS: Pointer in formal parameters");
        ic.clearDereference();
        ic.clear(functionName);
        fact = factParams.get(i).accept(ic);
        if (flowSense) {
          pResult.clearAlias(form);
        }
        pResult.addAlias(form, fact, logger);
        // rcu_assign_pointer(gp, p); || rcu_dereference(gp);
        if (fd.getName().contains(assign) || fd.getName().contains(deref)) {
          addToRCU(pResult, fact);
        }
      }
    }
  }

  private AbstractIdentifier handleDeclaration(AliasState pResult, CDeclaration pCdecl,
                                               IdentifierCreator ic, String functionName) {
    if (pCdecl != null && pCdecl instanceof CVariableDeclaration) {
      logger.log(Level.ALL, "ALIAS: OK declaration");
      CVariableDeclaration var = (CVariableDeclaration) pCdecl;
      AbstractIdentifier ail = IdentifierCreator.createIdentifier(var, functionName, 0);
      if (ail.isPointer()) {
        logger.log(Level.ALL, "ALIAS: Pointer declaration");
        CInitializer init = var.getInitializer();
        AbstractIdentifier air;
        if (init != null) {
          if (init instanceof CInitializerExpression) {
            air = ((CInitializerExpression) init).getExpression().accept(ic);
            pResult.addAlias(ail, air, logger);
          }
        } else {
          pResult.addAlias(ail, null, logger);
        }
        return ail;
      }
    }
    return null;
  }
}
