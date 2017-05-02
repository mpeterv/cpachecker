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
import java.util.HashMap;
import java.util.HashSet;
import java.util.logging.LogManager;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.core.defaults.AbstractCPA;
import org.sosy_lab.cpachecker.core.defaults.AutomaticCPAFactory;
import org.sosy_lab.cpachecker.core.defaults.DelegateAbstractDomain;
import org.sosy_lab.cpachecker.core.interfaces.AbstractDomain;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.CPAFactory;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.MergeOperator;
import org.sosy_lab.cpachecker.core.interfaces.StateSpacePartition;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.interfaces.StatisticsProvider;
import org.sosy_lab.cpachecker.core.interfaces.StopOperator;
import org.sosy_lab.cpachecker.core.interfaces.TransferRelation;

//TODO extend from AbstractCPA
@Options(prefix = "cpa.alias")
public class AliasCPA extends AbstractCPA implements ConfigurableProgramAnalysis,
                                                     StatisticsProvider{

  public static CPAFactory factory() {
    return AutomaticCPAFactory.forType(AliasCPA.class);
  }

  private final Configuration config;
  private final CFA cfa;
  private final LogManager log;

  protected AliasCPA(Configuration config, LogManager log, CFA cfa)
      throws InvalidConfigurationException {
    super("JOIN", "SEP", DelegateAbstractDomain.<AliasState>getInstance(),
          new AliasTransfer(config, log));
    config.inject(this);
    this.config = config;
    this.log = log;
    this.cfa = cfa;
  }

  @Override
  public AbstractDomain getAbstractDomain() {
    return ((AbstractCPA) this).getAbstractDomain();
  }

  @Override
  public TransferRelation getTransferRelation() {
    return ((AbstractCPA) this).getTransferRelation();
  }

  @Override
  public MergeOperator getMergeOperator() {
    return ((AbstractCPA) this).getMergeOperator();
  }

  @Override
  public StopOperator getStopOperator() {
    return ((AbstractCPA) this).getStopOperator();
  }

  @Override
  public AbstractState getInitialState(
      CFANode node, StateSpacePartition partition) throws InterruptedException {
    return new AliasState(new HashMap<>(), new HashSet<>());
  }

  @Override
  public void collectStatistics(Collection<Statistics> statsCollection) {

  }
}
