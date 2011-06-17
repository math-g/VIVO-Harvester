/******************************************************************************************************************************
 * Copyright (c) 2011 Christopher Haines, Dale Scheppler, Nicholas Skaggs, Stephen V. Williams, James Pence, Michael Barbieri.
 * All rights reserved.
 * This program and the accompanying materials are made available under the terms of the new BSD license which accompanies this
 * distribution, and is available at http://www.opensource.org/licenses/bsd-license.html
 * Contributors:
 * Christopher Haines, Dale Scheppler, Nicholas Skaggs, Stephen V. Williams, James Pence, Michael Barbieri
 * - initial API and implementation
 *****************************************************************************************************************************/
package org.vivoweb.harvester.score.algorithm;

import org.apache.commons.codec.EncoderException;
import org.apache.commons.codec.language.Soundex;

/**
 * Normalized SoundEx Difference Algorithm
 * @author Christopher Haines hainesc@ctrip.ufl.edu
 */
public class NormalizedSoundExDifference implements Algorithm {
	
	@Override
	public float calculate(CharSequence itemX, CharSequence itemY) {
		try {
			int diff = Soundex.US_ENGLISH.difference(itemX.toString(), itemY.toString());
			return (diff / 4f);
		} catch(EncoderException e) {
			throw new IllegalArgumentException(e);
		}
	}
	
}
