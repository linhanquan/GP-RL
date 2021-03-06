package com.hccl.nlip

/**
 * Created by Superfan on 2014/8/28.
 */

import com.typesafe.scalalogging.LazyLogging
import org.jfree.data.xy.{VectorXYDataset, VectorSeriesCollection, VectorSeries}
import scala.collection.mutable.ArrayBuffer
import breeze.linalg._
import Math._

/**
 * GPSARSA algorithm as shown in Algo.20 of
 * ''Algorithms and Representations for Reinforcement Learning'' by Yaakove Engel,
 * using sparse kernel approximation to accelerate computation
 * @param initialState
 * @param initialAction
 */

class GPSARSASparseController(initialState: MazeState,
                        initialAction: MazeAction)
extends Controller with LazyLogging
{
    //Meta-parameters
    var nu: Double = 0.1
    var sigma: Double = 1
    var gammaVal = 0.9
    def gamma = if(episodeEnd) gammaVal else gammaVal
    var succssfulEpisods: Int = 0
    var episodeEnd = false
    var fixedEpsilon = 0.1
    var useFixedEpsilon = false
    def epsilon = {
        if (useFixedEpsilon)
            fixedEpsilon
        else
            100.0 / (100.0 + succssfulEpisods)
    }

    //Kernel parameters
    val sigma_state = 0.2
    val c_state = 10
    val b_action = 0.1

    //Initialize variables used in RL
    var dict = ArrayBuffer((initialState, initialAction))
    var K_tilde_inv = DenseMatrix(1.0 / fullKernel(initialState, initialAction))
    var a = DenseVector(1.0)
    var alpha_tilde = DenseVector.zeros[Double](1)
    var C_tilde = DenseMatrix.zeros[Double](1, 1)
    var c_tilde = DenseVector.zeros[Double](1)
    var d: Double = 0.0
    var s: Double = Double.PositiveInfinity

    def setGamma(gamma: Double): Unit = {
        this.gammaVal = gamma
    }

    def setFixedEpsilon(epsilon: Double): Unit = {
        this.fixedEpsilon = epsilon
    }

    def setUseFixedEpsilon(useFixedEpsilon: Boolean): Unit = {
        this.useFixedEpsilon = useFixedEpsilon
    }

    def stateKernel(s1: MazeState, s2: MazeState): Double = {
        val temp = s1.location - s2.location
        c_state * math.exp(-(temp.X * temp.X + temp.Y * temp.Y)/(2 * sigma_state * sigma_state))
    }

    def stateKernel(s: MazeState): Double = stateKernel(s, s)

    def getDictSize = dict.length

    def actionKernel(a1: MazeAction, a2: MazeAction): Double = {
        val u1 = DenseVector[Double](math.cos(a1.radiansToMove),
            math.sin(a1.radiansToMove))
        val u2 = DenseVector[Double](math.cos(a2.radiansToMove),
            math.sin(a2.radiansToMove))
        1 + (1 - b_action) / 2 * (u1.dot(u2) - 1)
    }

    def actionKernel(a: MazeAction): Double = actionKernel(a, a)

    def fullKernel(s1: MazeState, s2: MazeState,
                   a1: MazeAction, a2: MazeAction): Double =
        stateKernel(s1, s2) * actionKernel(a1, a2)

    def fullKernel(s: MazeState, a: MazeAction): Double =
        stateKernel(s) * actionKernel(a)

    def getKVector(s: MazeState, a: MazeAction) = {
        val k = DenseVector.zeros[Double](dict.length)
        for (i <- 0 until dict.length)
            k(i) = fullKernel(dict(i)._1, s, dict(i)._2, a)
        k
    }

    // This is currently coupled with the definition of the action kernel
    def getBestAngleToMove(s: MazeState): Double = {
        val k_state = {
            val k = DenseVector.zeros[Double](dict.length)
            for (i <- 0 until dict.length)
                k(i) = stateKernel(dict(i)._1, s)
            k
        }
        val beta = k_state :* alpha_tilde
        val u = DenseVector.zeros[Double](2)
        for (((_, action), i) <- dict.zipWithIndex)
            u :+= DenseVector(cos(action.radiansToMove),
                sin(action.radiansToMove)) * beta(i)
        val a = atan2(u(1), u(0))
        if (a < 0)
            a + 2 * PI
        else
            a
    }

    def getPolicy: MazePolicy = {
        new MazePolicy {
            override def getAction(s: MazeState): MazeAction = {
                if (dict.length == 1 || random < epsilon)
                    MazeAction.randomAction
                else
                    MazeAction(getBestAngleToMove(s))
            }
        }
    }

    override def getActionVectorSeries(step: Double,
                                       empty: Boolean = false): VectorXYDataset = {
        if (empty) {
            val localVectorSeriesCollection = new VectorSeriesCollection()
            localVectorSeriesCollection.addSeries(new VectorSeries("Predicted action"))
            localVectorSeriesCollection
        }
        else {
            val r = 60.0
            val localVectorSeries = new VectorSeries("Predicted action")
            var x = MazeEnvironment.xLower
            var y = MazeEnvironment.yLower
            while(x < MazeEnvironment.xUpper) {
                y = MazeEnvironment.yLower
                while(y < MazeEnvironment.yUpper) {
                    val ang = getBestAngleToMove(MazeState(Point2D(x, y)))
                    localVectorSeries.add(x, y, cos(ang)/r, sin(ang)/r)
                    y += step
                }
                x += step
            }
            val localVectorSeriesCollection = new VectorSeriesCollection()
            localVectorSeriesCollection.addSeries(localVectorSeries)
            localVectorSeriesCollection
        }
    }

    //One SARSA step
    def observeStep(oldState: MazeState,
                    oldAction: MazeAction,
                    reward: Double,
                    newState: MazeState,
                    newAction: MazeAction): Unit = {
        if (reward == MazeEnvironment.rewardOfReachingGoal) {
            succssfulEpisods += 1
            episodeEnd = true
        }
        else {
            episodeEnd = false
        }

        val k_tilde = getKVector(newState, newAction)
        val a_prev = a
        a = K_tilde_inv * k_tilde
        val delta = fullKernel(newState, newAction) - k_tilde.dot(a)
        val k_tilde_prev = getKVector(oldState, oldAction)
        val delta_k_tilde = k_tilde_prev - k_tilde * gamma
        val lambda = gamma * sigma * sigma / s
        d = d * lambda + reward - delta_k_tilde.dot(alpha_tilde)
        if (delta > nu) {
            // Update K_tilde_inv
            K_tilde_inv = {
                val K_tilde_inv_t =
                    DenseMatrix.zeros[Double](K_tilde_inv.rows + 1, K_tilde_inv.cols + 1)
                K_tilde_inv_t(0 to -2, 0 to -2) := K_tilde_inv * delta + a * a.t
                K_tilde_inv_t(0 to -2, -1) := -a
                K_tilde_inv_t(-1, 0 to -2) := a.t * -1.0
                K_tilde_inv_t(-1, -1) = 1.0
                K_tilde_inv_t :/= delta
                K_tilde_inv_t
            }

            a = DenseVector.zeros(a.length + 1)
            a(-1) = 1
            val h_tilde = DenseVector.zeros[Double](a.length)
            h_tilde(0 to -2) := a_prev
            h_tilde(-1) = -gamma
            val delta_k = a_prev.dot(k_tilde_prev - k_tilde * 2.0 * gamma) +
             gamma * gamma * fullKernel(newState, newAction)
            s = (1 + gamma * gamma) * sigma * sigma + delta_k -
             delta_k_tilde.t * C_tilde * delta_k_tilde +
             2 * lambda * c_tilde.dot(delta_k_tilde) - lambda * gamma * sigma * sigma
            c_tilde = {
                val temp1 = DenseVector.zeros[Double](c_tilde.length + 1)
                val temp2 = DenseVector.zeros[Double](c_tilde.length + 1)
                temp1(0 to -2) := c_tilde
                temp2(0 to -2) := C_tilde * delta_k_tilde
                temp1 * lambda + h_tilde - temp2
            }
            alpha_tilde = {
                val temp = DenseVector.zeros[Double](alpha_tilde.length + 1)
                temp(0 to -2) := alpha_tilde
                temp
            }
            C_tilde = {
                val temp = DenseMatrix.zeros[Double](C_tilde.rows + 1, C_tilde.cols + 1)
                temp(0 to -2, 0 to -2) := C_tilde
                temp
            }
            dict += ((newState, newAction))
            logger.debug(s"State/Action dictionary size: ${dict.length}")
            if (c_tilde(0)==Double.PositiveInfinity)
                logger.debug(s"NaN detected")
        } else {
            val h_tilde = a_prev - a * gamma
            val delta_k = h_tilde.dot(delta_k_tilde)
            val c_tilde_prev = c_tilde
            c_tilde = c_tilde * lambda + h_tilde - C_tilde * delta_k_tilde
            if (c_tilde(0)==Double.PositiveInfinity)
                logger.debug(s"PositiveInfinity detected")
            s = (1 + gamma * gamma) * sigma * sigma +
             delta_k_tilde.dot(c_tilde + c_tilde_prev * lambda) -
             lambda * gamma * sigma * sigma
        }
        alpha_tilde = alpha_tilde + c_tilde / s * d
        C_tilde = C_tilde + c_tilde * c_tilde.t / s
//        logger.debug(s"Epsilon: $epsilon")
    }
}

/**
 * A direct and unoptimized version of the GPTD algorithms,
 * this is typically to slow for any pratical use
 * @param initialState
 * @param initialAction
 */

class GPSARSAUnoptimizedSparseControler(initialState: MazeState,
                            initialAction: MazeAction)
extends GPSARSASparseController(initialState, initialAction) {
    var r = DenseVector.zeros[Double](0)
    var A = DenseMatrix((1.0))
    var t = 0

    def getH: DenseMatrix[Double]= {
        val H = DenseMatrix.zeros[Double](t, t + 1)
        for (i <- 0 until H.rows) {
            H(i, i) = 1
            H(i, i + 1) = -gamma
        }
        H
    }

    def getK: DenseMatrix[Double]= {
        val K = DenseMatrix.zeros[Double](dict.length, dict.length)
        for (i <- 0 until K.rows)
            for (j <- 0 until K.cols) {
                K(i, j) = fullKernel(dict(i)._1, dict(j)._1, dict(i)._2, dict(j)._2)
            }
        K
    }

    override def observeStep(oldState: MazeState,
                    oldAction: MazeAction,
                    reward: Double,
                    newState: MazeState,
                    newAction: MazeAction): Unit = {
        if (reward == MazeEnvironment.rewardOfReachingGoal)
            succssfulEpisods += 1
        val k_tilde = getKVector(newState, newAction)
        t += 1
        a = K_tilde_inv * k_tilde
        val delta = fullKernel(newState, newAction) - k_tilde.dot(a)
        r= {
            val new_r = DenseVector.zeros[Double](r.length + 1)
            new_r(0 to -2) := r
            new_r(-1) = reward
            new_r
        }
        if (delta > nu) {
            dict += ((newState, newAction))
            A = {
                val newA = DenseMatrix.zeros[Double](A.rows + 1, A.cols + 1)
                newA(0 to -2, 0 to -2) := A
                newA(-1, -1) = 1.0
                newA
            }
            logger.debug(s"State/Action dictionary size: ${dict.length}")
        }
        else {
            A = {
                val newA = DenseMatrix.zeros[Double](A.rows + 1, A.cols)
                newA(0 to -2, ::) := A
                newA(-1, ::) := a.t
                newA
            }
        }
        val H = getH
        val H_tilde = H * A
        val K_tilde = getK
        K_tilde_inv = inv(K_tilde)
        val Sigma = H_tilde * H_tilde.t * sigma * sigma
//        H.t * inv(H * K_tilde * H.t + Sigma) * r
        alpha_tilde = H_tilde.t * inv(H_tilde * K_tilde * H_tilde.t + Sigma) * r
//        logger.debug(s"Epsilon: $epsilon")
    }
}