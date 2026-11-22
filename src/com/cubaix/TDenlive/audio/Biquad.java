package com.cubaix.TDenlive.audio;

/**
 * Second order IIR filter (formulas of the Audio EQ Cookbook of R. Bristow-Johnson), with
 * its own state for each of the stereo channels
 */
public class Biquad {
	double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
	final double[] x1 = new double[2], x2 = new double[2], y1 = new double[2], y2 = new double[2];

	public static Biquad of(double aB0,double aB1,double aB2,double aA0,double aA1,double aA2) {
		Biquad aB = new Biquad();
		aB.set(aB0, aB1, aB2, aA0, aA1, aA2);
		return aB;
	}

	void set(double aB0,double aB1,double aB2,double aA0,double aA1,double aA2) {
		b0 = aB0/aA0;
		b1 = aB1/aA0;
		b2 = aB2/aA0;
		a1 = aA1/aA0;
		a2 = aA2/aA0;
	}

	/** Peaking filter of aGainDb at aFreq */
	public void peaking(double aRate,double aFreq,double aQ,double aGainDb) {
		double aA = Math.pow(10, aGainDb/40), aW = 2*Math.PI*aFreq/aRate, aAl = Math.sin(aW)/(2*aQ), aC = Math.cos(aW);
		set(1+aAl*aA, -2*aC, 1-aAl*aA, 1+aAl/aA, -2*aC, 1-aAl/aA);
	}

	/** Low shelf of aGainDb below aFreq (slope 1) */
	public void lowShelf(double aRate,double aFreq,double aGainDb) {
		double aA = Math.pow(10, aGainDb/40), aW = 2*Math.PI*aFreq/aRate, aC = Math.cos(aW);
		double aAl = Math.sin(aW)/2*Math.sqrt(2), aSq = 2*Math.sqrt(aA)*aAl;
		set(aA*((aA+1)-(aA-1)*aC+aSq), 2*aA*((aA-1)-(aA+1)*aC), aA*((aA+1)-(aA-1)*aC-aSq)
				, (aA+1)+(aA-1)*aC+aSq, -2*((aA-1)+(aA+1)*aC), (aA+1)+(aA-1)*aC-aSq);
	}

	/** High shelf of aGainDb above aFreq (slope 1) */
	public void highShelf(double aRate,double aFreq,double aGainDb) {
		double aA = Math.pow(10, aGainDb/40), aW = 2*Math.PI*aFreq/aRate, aC = Math.cos(aW);
		double aAl = Math.sin(aW)/2*Math.sqrt(2), aSq = 2*Math.sqrt(aA)*aAl;
		set(aA*((aA+1)+(aA-1)*aC+aSq), -2*aA*((aA-1)+(aA+1)*aC), aA*((aA+1)+(aA-1)*aC-aSq)
				, (aA+1)-(aA-1)*aC+aSq, 2*((aA-1)-(aA+1)*aC), (aA+1)-(aA-1)*aC-aSq);
	}

	/** High pass (Butterworth with aQ = 0.707) */
	public void highPass(double aRate,double aFreq,double aQ) {
		double aW = 2*Math.PI*aFreq/aRate, aAl = Math.sin(aW)/(2*aQ), aC = Math.cos(aW);
		set((1+aC)/2, -(1+aC), (1+aC)/2, 1+aAl, -2*aC, 1-aAl);
	}

	/** Low pass (Butterworth with aQ = 0.707) */
	public void lowPass(double aRate,double aFreq,double aQ) {
		double aW = 2*Math.PI*aFreq/aRate, aAl = Math.sin(aW)/(2*aQ), aC = Math.cos(aW);
		set((1-aC)/2, 1-aC, (1-aC)/2, 1+aAl, -2*aC, 1-aAl);
	}

	public double process(double aX,int aCh) {
		double aY = b0*aX+b1*x1[aCh]+b2*x2[aCh]-a1*y1[aCh]-a2*y2[aCh];
		x2[aCh] = x1[aCh];
		x1[aCh] = aX;
		y2[aCh] = y1[aCh];
		y1[aCh] = aY;
		return aY;
	}

	public void reset() {
		for(int c = 0;c < 2;c++) {
			x1[c] = x2[c] = y1[c] = y2[c] = 0;
		}
	}
}
