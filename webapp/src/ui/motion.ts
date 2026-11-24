// One answer to "may this move?", read where it is used so a change in the system setting counts at once.

/** The viewer's own word in Settings, Reduce motion: Automatic follows the system, On and Off decide (html[data-motion], store.ts). */
export type MotionChoice = 'auto' | 'on' | 'off'

/** What the system and the viewer's choice say together. `system` is the browser's `prefers-reduced-motion: reduce`. */
export const motionReduced = (choice: MotionChoice | string | undefined, system: boolean): boolean => (choice === 'on' ? true : choice === 'off' ? false : system)

/** True where the viewer asked for less motion: in Settings, or, under Automatic, of their system. */
export const reducedMotion = () => {
    const choice = typeof document === 'undefined' ? undefined : document.documentElement.dataset.motion
    const system = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
    return motionReduced(choice === 'reduce' ? 'on' : choice === 'full' ? 'off' : 'auto', system)
}

/** `behavior` for a scroll the page starts itself: it glides, or jumps where motion is reduced. */
export const scrollBehavior = (): ScrollBehavior => (reducedMotion() ? 'auto' : 'smooth')
