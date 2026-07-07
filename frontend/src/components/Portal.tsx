import { createPortal } from "react-dom";
import type { ReactNode } from "react";

/* Renders overlays (modals / drawers) into document.body.
   Needed because page containers animate transform (.op-fade), and a
   transform-animated ancestor becomes the containing block for
   position: fixed descendants — clipping/offsetting the overlay. */
export default function Portal({ children }: { children: ReactNode }) {
  return createPortal(children, document.body);
}
