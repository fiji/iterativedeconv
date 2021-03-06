/*
 *  Copyright (C) 2008-2009 Piotr Wendykier
 *  
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 3 of the License, or
 *  (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *  
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package edu.emory.mathcs.restoretools.iterative.hybr;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import optimization.DoubleFmin;
import optimization.DoubleFmin_methods;
import cern.colt.list.tdouble.DoubleArrayList;
import cern.colt.matrix.tdouble.DoubleFactory2D;
import cern.colt.matrix.tdouble.DoubleMatrix1D;
import cern.colt.matrix.tdouble.DoubleMatrix2D;
import cern.colt.matrix.tdouble.algo.decomposition.DenseDoubleSingularValueDecompositionDC;
import cern.colt.matrix.tdouble.algo.solver.HyBRInnerSolver;
import cern.colt.matrix.tdouble.algo.solver.HyBRRegularizationMethod;
import cern.colt.matrix.tdouble.impl.DenseColumnDoubleMatrix2D;
import cern.colt.matrix.tdouble.impl.DenseDoubleMatrix1D;
import cern.colt.matrix.tdouble.impl.DenseDoubleMatrix2D;
import cern.jet.math.tdouble.DoubleFunctions;
import cern.jet.stat.tdouble.DoubleDescriptive;
import edu.emory.mathcs.restoretools.Enums.OutputType;
import edu.emory.mathcs.restoretools.iterative.AbstractDoubleIterativeDeconvolver3D;
import edu.emory.mathcs.restoretools.iterative.DoubleCommon2D;
import edu.emory.mathcs.restoretools.iterative.DoubleCommon3D;
import edu.emory.mathcs.restoretools.iterative.IterativeEnums.BoundaryType;
import edu.emory.mathcs.restoretools.iterative.IterativeEnums.PreconditionerType;
import edu.emory.mathcs.restoretools.iterative.IterativeEnums.ResizingType;
import edu.emory.mathcs.restoretools.iterative.preconditioner.DoublePreconditioner3D;
import edu.emory.mathcs.restoretools.iterative.psf.DoublePSFMatrix3D;

/**
 * Hybrid Bidiagonalization Regularization 3D.
 * 
 * @author Piotr Wendykier (piotr.wendykier@gmail.com)
 * 
 */
public class HyBRDoubleIterativeDeconvolver3D extends AbstractDoubleIterativeDeconvolver3D {

    /**
     * Inner solver.
     */
    private HyBRInnerSolver innerSolver;

    /**
     * Regularization method.
     */
    private HyBRRegularizationMethod regMethod;

    /**
     * Regularization parameter.
     */
    private double regParam;

    /**
     * Omega parameter for weighted GCV.
     */
    private double omega;

    /**
     * If true then the reorthogonalization of Lanczos subspaces is performed.
     */
    private boolean reorth;

    /**
     * Begin regularization after this iteration.
     */
    private int begReg;

    /**
     * Tolerance for detecting flatness in the GCV curve as a % stopping
     * criteria.
     */
    private double flatTol;

    /**
     * The size of blurred image.
     */
    private int[] bsize;

    /**
     * Creates a new instance of HyBRDoubleIterativeDeconvolver3D
     * 
     * @param imB
     *            blurred image
     * @param imPSF
     *            Point Spread Function
     * @param preconditioner
     *            type of a preconditioner
     * @param preconditionerTol
     *            tolerance for the preconditioner
     * @param boundary
     *            type of boundary conditions
     * @param resizing
     *            type of resizing
     * @param output
     *            type of the output image
     * @param maxIters
     *            maximal number of iterations
     * @param showIteration
     *            if true, then the restored image is displayed after each
     *            iteration
     * @param options
     *            HyBR options
     */
    public HyBRDoubleIterativeDeconvolver3D(ImagePlus imB, ImagePlus[][][] imPSF, PreconditionerType preconditioner, double preconditionerTol, BoundaryType boundary, ResizingType resizing, OutputType output, int maxIters, boolean showIteration, HyBROptions options) {
        super("HyBR", imB, imPSF, preconditioner, preconditionerTol, boundary, resizing, output, options.getUseThreshold(), options.getThreshold(), maxIters, showIteration, options.getLogConvergence());
        this.innerSolver = options.getInnerSolver();
        this.regMethod = options.getRegMethod();
        this.regParam = options.getRegParameter();
        this.omega = options.getOmega();
        this.reorth = options.getReorthogonalize();
        this.begReg = options.getBeginReg();
        this.flatTol = options.getFlatTolerance();
        bsize = new int[] { bSlices, bRows, bColumns };
    }

    
    public ImagePlus deconvolve() {
        int k;
        int columns = A.getSize()[1];
        boolean bump = false;
        boolean warning = false;
        double rnrm = -1.0;
        int iterationsSave = 0;
        double alpha, beta;
        HyBRInnerSolver inSolver = HyBRInnerSolver.NONE;
        DoubleLBD lbd;
        DoubleMatrix1D v;
        DoubleMatrix1D work;
        DoubleMatrix2D Ub, Vb;
        DoubleMatrix1D f = null;
        DoubleMatrix1D x = null;
        DoubleMatrix1D xSave = null;
        double[] sv;
        DoubleArrayList omegaList = new DoubleArrayList(new double[begReg - 2]);
        DoubleArrayList GCV = new DoubleArrayList(new double[begReg - 2]);
        DoubleMatrix1D b = new DenseDoubleMatrix1D((int)B.size(), (double[]) B.elements(), 0, 1, false);
        DoubleMatrix2D U = new DenseDoubleMatrix2D(1, (int)B.size());
        DoubleMatrix2D C = null;
        DoubleMatrix2D V = null;
        DenseDoubleSingularValueDecompositionDC svd;
        if (P == null) {
            beta = alg.norm2(b);
            U.viewRow(0).assign(b, DoubleFunctions.multSecond(1.0 / beta));
            lbd = new DoubleSimpleLBD(A, U, reorth);
        } else {
            work = P.solve(b, false);
            beta = alg.norm2(work);
            U.viewRow(0).assign(work, DoubleFunctions.multSecond(1.0 / beta));
            lbd = new DoublePLBD(P, A, U, reorth);
        }
        ImagePlus imX = null;
        ImageStack is = new ImageStack(bColumns, bRows);
        if (showIteration) {
            DoubleCommon3D.assignPixelsToStack(is, B, cmY);
            imX = new ImagePlus("(deblurred)", is);
            imX.show();
            IJ.showStatus("HyBR initialization...");
        }
        for (k = 0; k <= maxIters; k++) {
            lbd.apply();
            U = lbd.getU();
            C = lbd.getC();
            V = lbd.getV();
            v = new DenseDoubleMatrix1D(C.columns() + 1);
            v.setQuick(0, beta);
            if (k >= 1) {
                IJ.showStatus("HyBR iteration: " + k + "/" + maxIters);
                if (k >= begReg - 1) {
                    inSolver = innerSolver;
                }
                switch (inSolver) {
                case TIKHONOV:
                    svd = alg.svdDC(C);
                    Ub = svd.getU();
                    sv = svd.getSingularValues();
                    Vb = svd.getV();
                    if (regMethod == HyBRRegularizationMethod.ADAPTWGCV) {
                        work = new DenseDoubleMatrix1D(Ub.rows());
                        Ub.zMult(v, work, 1, 0, true);
                        omegaList.add(Math.min(1, findOmega(work, sv)));
                        omega = DoubleDescriptive.mean(omegaList);
                    }
                    f = new DenseDoubleMatrix1D(Vb.rows());
                    alpha = tikhonovSolver(Ub, sv, Vb, v, f);
                    GCV.add(GCVstopfun(alpha, Ub.viewRow(0), sv, beta, columns));
                    if (k > 1) {
                        if (Math.abs((GCV.getQuick(k - 1) - GCV.getQuick(k - 2))) / GCV.get(begReg - 2) < flatTol) {
                            x = V.zMult(f, null);
                            if (logConvergence) {
                                work = A.times(x, false);
                                rnrm = alg.norm2(work.assign(b, DoubleFunctions.minus));
                                IJ.log(k + ".  Norm of the residual = " + rnrm);
                                IJ.log("HyBR stopped after " + k + " iterations.");
                                IJ.log("Reason for stopping: flat GCV curve.");
                            }
                            if (showIteration == false) {
                                if (useThreshold) {
                                    DoubleCommon3D.assignPixelsToStack(is, x, bsize, cmY, threshold);
                                } else {
                                    DoubleCommon3D.assignPixelsToStack(is, x, bsize, cmY);
                                }
                                imX = new ImagePlus("(deblurred)", is);
                            } else {
                                if (useThreshold) {
                                    DoubleCommon3D.updatePixelsInStack(is, x, bsize, cmY, threshold);
                                } else {
                                    DoubleCommon3D.updatePixelsInStack(is, x, bsize, cmY);
                                }
                                imX.updateAndDraw();
                            }
                            DoubleCommon3D.convertImage(imX, output);
                            return imX;
                        } else if ((warning == true) && (GCV.size() > iterationsSave + 3)) {
                            for (int j = iterationsSave; j < GCV.size(); j++) {
                                if (GCV.getQuick(iterationsSave - 1) > GCV.get(j)) {
                                    bump = true;
                                }
                            }
                            if (bump == false) {
                                x.assign(xSave);
                                if (logConvergence) {
                                    work = A.times(x, false);
                                    rnrm = alg.norm2(work.assign(b, DoubleFunctions.minus));
                                    IJ.log("HyBR stopped after " + iterationsSave + " iterations.");
                                    IJ.log("Reason for stopping: min of GCV curve within window of 4 iterations.");
                                }
                                if (showIteration == false) {
                                    if (useThreshold) {
                                        DoubleCommon3D.assignPixelsToStack(is, x, bsize, cmY, threshold);
                                    } else {
                                        DoubleCommon3D.assignPixelsToStack(is, x, bsize, cmY);
                                    }
                                    imX = new ImagePlus("(deblurred)", is);
                                } else {
                                    if (useThreshold) {
                                        DoubleCommon3D.updatePixelsInStack(is, x, bsize, cmY, threshold);
                                    } else {
                                        DoubleCommon3D.updatePixelsInStack(is, x, bsize, cmY);
                                    }
                                    imX.updateAndDraw();
                                }
                                DoubleCommon3D.convertImage(imX, output);
                                return imX;
                            } else {
                                bump = false;
                                warning = false;
                                iterationsSave = maxIters;
                            }
                        } else if (warning == false) {
                            if (GCV.get(k - 2) < GCV.get(k - 1)) {
                                warning = true;
                                xSave = V.zMult(f, null);
                                iterationsSave = k;
                            }
                        }
                    }
                    break;
                case NONE:
                    f = alg.solve(C, v);
                    break;
                }
                x = V.zMult(f, null);
                if (logConvergence) {
                    work = A.times(x, false);
                    rnrm = alg.norm2(work.assign(b, DoubleFunctions.minus));
                    IJ.log(k + ".  Norm of the residual = " + rnrm);
                }
                if (showIteration == true) {
                    if (useThreshold) {
                        DoubleCommon3D.updatePixelsInStack(is, x, bsize, cmY, threshold);
                    } else {
                        DoubleCommon3D.updatePixelsInStack(is, x, bsize, cmY);
                    }
                    imX.updateAndDraw();
                }
            }
        }
        if (logConvergence && k == (maxIters + 1)) {
            IJ.log("HyBR didn't converge. Reason: maximum number of iterations performed.");
        }
        if (showIteration == false) {
            if (useThreshold) {
                DoubleCommon3D.assignPixelsToStack(is, x, bsize, cmY, threshold);
            } else {
                DoubleCommon3D.assignPixelsToStack(is, x, bsize, cmY);
            }
            imX = new ImagePlus("(deblurred)", is);
        }
        DoubleCommon3D.convertImage(imX, output);
        return imX;

    }

    private double findOmega(DoubleMatrix1D bhat, double[] s) {
        int m = (int)bhat.size();
        int n = s.length;
        double alpha = s[n - 1];
        double t0 = bhat.viewPart(n, m - n).aggregate(DoubleFunctions.plus, DoubleFunctions.square);
        DoubleMatrix1D s2 = new DenseDoubleMatrix1D(s);
        s2.assign(DoubleFunctions.square);
        double alpha2 = alpha * alpha;
        DoubleMatrix1D tt = s2.copy();
        tt.assign(DoubleFunctions.plus(alpha2));
        tt.assign(DoubleFunctions.inv);
        double t1 = s2.aggregate(tt, DoubleFunctions.plus, DoubleFunctions.mult);
        s2 = new DenseDoubleMatrix1D(s);
        s2.assign(DoubleFunctions.mult(alpha));
        s2.assign(bhat.viewPart(0, n), DoubleFunctions.mult);
        s2.assign(DoubleFunctions.square);
        DoubleMatrix1D work = tt.copy();
        work.assign(DoubleFunctions.pow(3));
        work.assign(DoubleFunctions.abs);
        double t3 = work.aggregate(s2, DoubleFunctions.plus, DoubleFunctions.mult);
        work = new DenseDoubleMatrix1D(s);
        work.assign(tt, DoubleFunctions.mult);
        double t4 = work.aggregate(DoubleFunctions.plus, DoubleFunctions.square);
        work = tt.copy();
        work.assign(bhat.viewPart(0, n), DoubleFunctions.mult);
        work.assign(DoubleFunctions.mult(alpha2));
        double t5 = work.aggregate(DoubleFunctions.plus, DoubleFunctions.square);
        s2 = new DenseDoubleMatrix1D(s);
        s2.assign(bhat.viewPart(0, n), DoubleFunctions.mult);
        s2.assign(DoubleFunctions.square);
        tt.assign(DoubleFunctions.pow(3));
        tt.assign(DoubleFunctions.abs);
        double v2 = tt.aggregate(s2, DoubleFunctions.plus, DoubleFunctions.mult);
        return (m * alpha2 * v2) / (t1 * t3 + t4 * (t5 + t0));
    }

    private double tikhonovSolver(DoubleMatrix2D U, double[] s, DoubleMatrix2D V, DoubleMatrix1D b, DoubleMatrix1D x) {
        TikFmin fmin;
        DoubleMatrix1D bhat = new DenseDoubleMatrix1D(U.rows());
        U.zMult(b, bhat, 1, 0, true);
        double alpha = 0;
        switch (regMethod) {
        case GCV:
            fmin = new TikFmin(bhat, s, 1);
            alpha = DoubleFmin.fmin(0, 1, fmin, DoubleCommon2D.FMIN_TOL);
            break;
        case WGCV:
            fmin = new TikFmin(bhat, s, omega);
            alpha = DoubleFmin.fmin(0, 1, fmin, DoubleCommon2D.FMIN_TOL);
            break;
        case ADAPTWGCV:
            fmin = new TikFmin(bhat, s, omega);
            alpha = DoubleFmin.fmin(0, 1, fmin, DoubleCommon2D.FMIN_TOL);
            break;
        case NONE: // regularization parameter is given
            alpha = regParam;
            break;
        }
        DoubleMatrix1D d = new DenseDoubleMatrix1D(s);
        d.assign(DoubleFunctions.square);
        d.assign(DoubleFunctions.plus(alpha * alpha));
        bhat = bhat.viewPart(0, s.length);
        DoubleMatrix1D S = new DenseDoubleMatrix1D(s);
        bhat.assign(S, DoubleFunctions.mult);
        bhat.assign(d, DoubleFunctions.div);
        V.zMult(bhat, x);
        return alpha;
    }

    private static class TikFmin implements DoubleFmin_methods {
        DoubleMatrix1D bhat;

        double[] s;

        double omega;

        public TikFmin(DoubleMatrix1D bhat, double[] s, double omega) {
            this.bhat = bhat;
            this.s = s;
            this.omega = omega;
        }

        public double f_to_minimize(double alpha) {
            int m = (int)bhat.size();
            int n = s.length;
            double t0 = bhat.viewPart(n, m - n).aggregate(DoubleFunctions.plus, DoubleFunctions.square);
            DoubleMatrix1D s2 = new DenseDoubleMatrix1D(s);
            s2.assign(DoubleFunctions.square);
            double alpha2 = alpha * alpha;
            DoubleMatrix1D work = s2.copy();
            work.assign(DoubleFunctions.plus(alpha2));
            work.assign(DoubleFunctions.inv);
            DoubleMatrix1D t1 = work.copy();
            t1.assign(DoubleFunctions.mult(alpha2));
            DoubleMatrix1D t2 = t1.copy();
            t2.assign(bhat.viewPart(0, n), DoubleFunctions.mult);
            DoubleMatrix1D t3 = work.copy();
            t3.assign(s2, DoubleFunctions.mult);
            t3.assign(DoubleFunctions.mult(1 - omega));
            double denom = t3.aggregate(t1, DoubleFunctions.plus, DoubleFunctions.plus) + m - n;
            return n * (t2.aggregate(DoubleFunctions.plus, DoubleFunctions.square) + t0) / (denom * denom);
        }

    }

    private static double GCVstopfun(double alpha, DoubleMatrix1D u, double[] s, double beta, int n) {
        int k = s.length;
        double beta2 = beta * beta;
        DoubleMatrix1D s2 = new DenseDoubleMatrix1D(s);
        s2.assign(DoubleFunctions.square);
        double alpha2 = alpha * alpha;
        DoubleMatrix1D t1 = s2.copy();
        t1.assign(DoubleFunctions.plus(alpha2));
        t1.assign(DoubleFunctions.inv);
        DoubleMatrix1D t2 = t1.copy();
        t2.assign(u.viewPart(0, k), DoubleFunctions.mult);
        t2.assign(DoubleFunctions.mult(alpha2));
        double num = beta2 * (t2.aggregate(DoubleFunctions.plus, DoubleFunctions.square) + Math.pow(Math.abs(u.getQuick(k)), 2)) / (double) n;
        double den = (n - t1.aggregate(s2, DoubleFunctions.plus, DoubleFunctions.mult)) / (double) n;
        den = den * den;
        return num / den;
    }

    private interface DoubleLBD {
        public void apply();

        public DoubleMatrix2D getC();

        public DoubleMatrix2D getU();

        public DoubleMatrix2D getV();
    }

    private class DoubleSimpleLBD implements DoubleLBD {
        private final DoubleFactory2D factory = DoubleFactory2D.dense;

        private final DoubleMatrix2D alphaBeta = new DenseDoubleMatrix2D(2, 1);

        private final DoublePSFMatrix3D A;

        private DoubleMatrix2D C;

        private DoubleMatrix2D U;

        private DoubleMatrix2D V;

        private boolean reorth;

        private int counter = 1;

        public DoubleSimpleLBD(DoublePSFMatrix3D A, DoubleMatrix2D U, boolean reorth) {
            this.A = A;
            this.reorth = reorth;
            this.U = U;
            this.V = null;
            this.C = null;
        }

        public void apply() {
            if (reorth) {
                int k = U.rows();
                DoubleMatrix1D u = null;
                DoubleMatrix1D v = null;
                DoubleMatrix1D column = null;
                if (k == 1) {
                    v = A.times(U.viewRow(k - 1), true);
                } else {
                    v = A.times(U.viewRow(k - 1), true);
                    column = V.viewColumn(k - 2);
                    v.assign(column, DoubleFunctions.plusMultSecond(-C.getQuick(k - 1, k - 2)));
                    for (int j = 0; j < k - 1; j++) {
                        column = V.viewColumn(j);
                        v.assign(column, DoubleFunctions.plusMultSecond(-column.zDotProduct(v)));
                    }
                }
                double alpha = alg.norm2(v);
                v.assign(DoubleFunctions.div(alpha));
                u = A.times(v, false);
                column = U.viewRow(k - 1);
                u.assign(column, DoubleFunctions.plusMultSecond(-alpha));
                for (int j = 0; j < k; j++) {
                    column = U.viewRow(j);
                    u.assign(column, DoubleFunctions.plusMultSecond(-column.zDotProduct(u)));
                }
                double beta = alg.norm2(u);
                alphaBeta.setQuick(0, 0, alpha);
                alphaBeta.setQuick(1, 0, beta);
                u.assign(DoubleFunctions.div(beta));
                U = factory.appendRow(U, u);
                if (V == null) {
                    V = new DenseColumnDoubleMatrix2D((int) v.size(), 1);
                    V.assign((double[]) v.elements());
                } else {
                    V = factory.appendColumn(V, v);
                }
                if (C == null) {
                    C = new DenseDoubleMatrix2D(2, 1);
                    C.assign(alphaBeta);
                } else {
                    C = factory.composeBidiagonal(C, alphaBeta);
                }
            } else {
                DoubleMatrix1D u = null;
                DoubleMatrix1D v = null;
                DoubleMatrix1D column = null;
                if (counter == 1) {
                    v = A.times(U.viewRow(0), true);
                } else {
                    v = A.times(U.viewRow(0), true);
                    column = V.viewColumn(counter - 2);
                    v.assign(column, DoubleFunctions.plusMultSecond(-C.getQuick(counter - 1, counter - 2)));
                }
                double alpha = alg.norm2(v);
                v.assign(DoubleFunctions.div(alpha));
                u = A.times(v, false);
                column = U.viewRow(0);
                u.assign(column, DoubleFunctions.plusMultSecond(-alpha));
                double beta = alg.norm2(u);
                alphaBeta.setQuick(0, 0, alpha);
                alphaBeta.setQuick(1, 0, beta);
                u.assign(DoubleFunctions.div(beta));
                U.viewRow(0).assign(u);
                if (V == null) {
                    V = new DenseColumnDoubleMatrix2D((int) v.size(), 1);
                    V.assign((double[]) v.elements());
                } else {
                    V = factory.appendColumn(V, v);
                }
                if (C == null) {
                    C = new DenseDoubleMatrix2D(2, 1);
                    C.assign(alphaBeta);
                } else {
                    C = factory.composeBidiagonal(C, alphaBeta);
                }
                counter++;
            }
        }

        public DoubleMatrix2D getC() {
            return C;
        }

        public DoubleMatrix2D getU() {
            return U;
        }

        public DoubleMatrix2D getV() {
            return V;
        }
    }

    private class DoublePLBD implements DoubleLBD {

        private final DoubleFactory2D factory = DoubleFactory2D.dense;

        private final DoubleMatrix2D alphaBeta = new DenseDoubleMatrix2D(2, 1);

        private final DoublePreconditioner3D P;

        private final DoublePSFMatrix3D A;

        private DoubleMatrix2D C;

        private DoubleMatrix2D U;

        private DoubleMatrix2D V;

        private boolean reorth;
        
        private int counter = 1;

        public DoublePLBD(DoublePreconditioner3D P, DoublePSFMatrix3D A, DoubleMatrix2D U, boolean reorth) {
            this.P = P;
            this.A = A;
            this.reorth = reorth;
            this.U = U;
            this.V = null;
            this.C = null;
        }

        public void apply() {
            if (reorth) {
                int k = U.rows();
                DoubleMatrix1D u = null;
                DoubleMatrix1D v = null;
                DoubleMatrix1D row = null;
                if (k == 1) {
                    row = U.viewRow(k - 1).copy();
                    row = P.solve(row, true);
                    v = A.times(row, true);
                } else {
                    row = U.viewRow(k - 1).copy();
                    row = P.solve(row, true);
                    v = A.times(row, true);
                    row = V.viewColumn(k - 2);
                    v.assign(row, DoubleFunctions.plusMultSecond(-C.getQuick(k - 1, k - 2)));
                    for (int j = 0; j < k - 1; j++) {
                        row = V.viewColumn(j);
                        v.assign(row, DoubleFunctions.plusMultSecond(-row.zDotProduct(v)));
                    }
                }
                double alpha = alg.norm2(v);
                v.assign(DoubleFunctions.div(alpha));
                row = A.times(v, false);
                u = P.solve(row, false);
                row = U.viewRow(k - 1);
                u.assign(row, DoubleFunctions.plusMultSecond(-alpha));
                for (int j = 0; j < k; j++) {
                    row = U.viewRow(j);
                    u.assign(row, DoubleFunctions.plusMultSecond(-row.zDotProduct(u)));
                }
                double beta = alg.norm2(u);
                alphaBeta.setQuick(0, 0, alpha);
                alphaBeta.setQuick(1, 0, beta);
                u.assign(DoubleFunctions.div(beta));
                U = factory.appendRow(U, u);
                if (V == null) {
                    V = new DenseColumnDoubleMatrix2D((int) v.size(), 1);
                    V.assign((double[]) v.elements());
                } else {
                    V = factory.appendColumn(V, v);
                }
                if (C == null) {
                    C = new DenseDoubleMatrix2D(2, 1);
                    C.assign(alphaBeta);
                } else {
                    C = factory.composeBidiagonal(C, alphaBeta);
                }
            } else {
                DoubleMatrix1D u = null;
                DoubleMatrix1D v = null;
                DoubleMatrix1D row = null;
                if (counter == 1) {
                    row = U.viewRow(0).copy();
                    row = P.solve(row, true);
                    v = A.times(row, true);
                } else {
                    row = U.viewRow(0).copy();
                    row = P.solve(row, true);
                    v = A.times(row, true);
                    row = V.viewColumn(counter - 2);
                    v.assign(row, DoubleFunctions.plusMultSecond(-C.getQuick(counter - 1, counter - 2)));
                }
                double alpha = alg.norm2(v);
                v.assign(DoubleFunctions.div(alpha));
                row = A.times(v, false);
                u = P.solve(row, false);
                row = U.viewRow(0);
                u.assign(row, DoubleFunctions.plusMultSecond(-alpha));
                double beta = alg.norm2(u);
                alphaBeta.setQuick(0, 0, alpha);
                alphaBeta.setQuick(1, 0, beta);
                u.assign(DoubleFunctions.div(beta));
                U.viewRow(0).assign(u);
                if (V == null) {
                    V = new DenseColumnDoubleMatrix2D((int) v.size(), 1);
                    V.assign((double[]) v.elements());
                } else {
                    V = factory.appendColumn(V, v);
                }
                if (C == null) {
                    C = new DenseDoubleMatrix2D(2, 1);
                    C.assign(alphaBeta);
                } else {
                    C = factory.composeBidiagonal(C, alphaBeta);
                }
                counter++;
            }
        }
        public DoubleMatrix2D getC() {
            return C;
        }

        public DoubleMatrix2D getU() {
            return U;
        }

        public DoubleMatrix2D getV() {
            return V;
        }
    }
}
