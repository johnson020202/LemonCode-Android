import * as React from "react";
import { Switch as SwitchPrimitive } from "radix-ui";

import { cn } from "../lib/utils.js";

function Switch({
  className,
  size = "default",
  ...props
}: React.ComponentProps<typeof SwitchPrimitive.Root> & {
  size?: "sm" | "default";
}) {
  return (
    <SwitchPrimitive.Root
      data-slot="switch"
      data-size={size}
      className={cn(
        // iOS 开关：开启为系统绿，关闭为低对比灰，尺寸按 51×31 的观感缩放。
        "p-px peer group/switch relative inline-flex shrink-0 items-center rounded-full transition-colors outline-none after:absolute after:-inset-x-3 after:-inset-y-2 focus-visible:ring-2 focus-visible:ring-input-border-focused/30 aria-invalid:ring-2 aria-invalid:ring-destructive/20 data-[size=default]:h-[26px] data-[size=default]:w-[44px] data-[size=sm]:h-[22px] data-[size=sm]:w-[38px] dark:aria-invalid:ring-destructive/40 data-checked:bg-[#34C759] data-unchecked:bg-foreground/20 data-disabled:cursor-not-allowed data-disabled:opacity-50",
        className,
      )}
      {...props}
    >
      <SwitchPrimitive.Thumb
        data-slot="switch-thumb"
        className="pointer-events-none block rounded-full bg-white shadow-sm ring-0 transition-transform group-data-[size=default]/switch:size-[24px] group-data-[size=sm]/switch:size-5 group-data-[size=default]/switch:data-checked:translate-x-[calc(100%-2px)] group-data-[size=sm]/switch:data-checked:translate-x-[calc(100%-2px)] group-data-[size=default]/switch:data-unchecked:translate-x-0 group-data-[size=sm]/switch:data-unchecked:translate-x-0"
      />
    </SwitchPrimitive.Root>
  );
}

export { Switch };
