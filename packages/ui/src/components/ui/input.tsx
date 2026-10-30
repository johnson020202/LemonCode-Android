import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "../lib/utils.js";

// iOS 输入框：填充式背景、默认无可见描边，聚焦时才出现主题色细边。
const inputVariants = cva(
  "w-full min-w-0 border border-transparent bg-input text-foreground transition-colors outline-none file:inline-flex file:border-0 file:bg-transparent file:font-medium file:text-foreground placeholder:text-foreground-subtlest hover:bg-input/80 focus-visible:border-input-border-focused focus-visible:bg-input-focused focus-visible:ring-0 disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-2 aria-invalid:ring-destructive/20 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40",
  {
    variants: {
      size: {
        xs: "h-6 rounded-sm px-2 py-0.5 text-ui-base file:h-4 file:text-ui-base",
        sm: "h-7 rounded-md px-2.5 py-0.5 text-ui-base/relaxed file:h-5 file:text-ui-base/relaxed",
        default:
          "h-9 rounded-lg px-3 py-1 text-ui-base md:text-ui-base/relaxed file:h-6 file:text-ui-base/relaxed",
        lg: "h-11 rounded-xl px-3.5 py-1.5 text-ui-base file:h-6 file:text-ui-base",
      },
    },
    defaultVariants: {
      size: "default",
    },
  },
);

type InputVariantProps = VariantProps<typeof inputVariants>;

interface InputProps extends Omit<React.ComponentProps<"input">, "size">, InputVariantProps {
  htmlSize?: number;
}

const Input = React.forwardRef<HTMLInputElement, InputProps>(function Input(
  { className, htmlSize, size = "default", type, ...props },
  ref,
) {
  return (
    <input
      ref={ref}
      type={type}
      size={htmlSize}
      data-slot="input"
      className={cn(inputVariants({ size }), className)}
      {...props}
    />
  );
});

export { Input, inputVariants, type InputProps };
