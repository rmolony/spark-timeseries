/**
 * Copyright (c) 2015, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */

package com.cloudera.finance

import org.apache.commons.math3.distribution.MultivariateNormalDistribution
import org.apache.commons.math3.random.{MersenneTwister, RandomGenerator}

/**
 * Version of MultivariateNormalDistribution that can be serialized in closures.
 */
class SerializableMultivariateNormalDistribution(
    rand: RandomGenerator,
    means: Array[Double],
    covariances: Array[Array[Double]])
  extends MultivariateNormalDistribution(rand, means, covariances) with Serializable {

  def this(means: Array[Double], covariances: Array[Array[Double]]) = {
    this(new MersenneTwister(), means, covariances)
  }
}
