import * as React from "react";

import { cn } from "../lib/utils.js";

const Textarea = React.forwardRef<HTMLTextAreaElement, React.ComponentProps<"textarea">>(
  function Textarea({ className, ...props }, ref) {
    return (
      <textarea
        ref={ref}
        data-slot="textarea"
        className={cn(
          // 与 Input 保持同一套 iOS 填充式观感。
          "flex field-sizing-content min-h-16 w-full resize-none rounded-lg border border-transparent bg-input px-3 py-2.5 text-ui-base transition-colors outline-none placeholder:text-foreground-subtlest focus-visible:border-input-border-focused focus-visible:ring-0 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-2 aria-invalid:ring-destructive/20 md:text-ui-base/relaxed",
          className,
        )}
        {...props}
      />
    );
  },
);
Textarea.displayName = "Textarea";

export { Textarea };
