// A tablet, for the layout choice (WP-62): a touch device whose screen is a tablet's, whatever size its window
// is now. Pure, so `node --test` runs it; form.ts reads the browser's numbers and hands them in.

/** Touch, and a short side of 600 px or more. */
export const isTablet = (touch: boolean, screenW: number, screenH: number): boolean => touch && Math.min(screenW, screenH) >= 600
