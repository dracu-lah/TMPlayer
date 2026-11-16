// One answer to "may this move?", read where it is used so a change in the system setting counts at once.

/** True where the viewer asked their system for less motion. */
export const reducedMotion = () => typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches

/** `behavior` for a scroll the page starts itself: it glides, or jumps where motion is reduced. */
export const scrollBehavior = (): ScrollBehavior => (reducedMotion() ? 'auto' : 'smooth')
