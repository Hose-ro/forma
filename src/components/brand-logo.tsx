import Image from 'next/image';

const assets = {
  primary: { src: '/brand/logo-primary.svg', width: 389, height: 100 },
  white: { src: '/brand/logo-white.svg', width: 389, height: 100 },
  dark: { src: '/brand/logo-dark.svg', width: 389, height: 100 },
  wordmark: { src: '/brand/logo-wordmark.svg', width: 273, height: 42 },
  isotype: { src: '/brand/isotype.svg', width: 100, height: 100 },
} as const;

type AssetVariant = keyof typeof assets;

type BrandLogoProps = {
  variant?: AssetVariant | 'adaptive';
  size?: 'xs' | 'sm' | 'md' | 'lg';
  className?: string;
  alt?: string;
  mobileIsotype?: boolean;
};

export function BrandLogo({ variant = 'primary', ...props }: BrandLogoProps) {
  if (variant !== 'adaptive') return <LogoImage {...props} variant={variant} />;

  return (
    <>
      <LogoImage {...props} variant="primary" themeClass="brand-logo--on-light" />
      <LogoImage {...props} variant="white" themeClass="brand-logo--on-dark" />
    </>
  );
}

function LogoImage({
  variant,
  size = 'md',
  className,
  alt = 'Olvenio',
  mobileIsotype = false,
  themeClass,
}: Omit<BrandLogoProps, 'variant'> & { variant: AssetVariant; themeClass?: string }) {
  const image = (
    <Image
      {...assets[variant]}
      alt={alt}
      className={[
        'brand-logo',
        `brand-logo--${size}`,
        variant === 'isotype' && 'brand-logo--isotype',
        !mobileIsotype && themeClass,
        className,
      ].filter(Boolean).join(' ')}
      loading="eager"
      unoptimized
    />
  );

  if (!mobileIsotype) return image;

  return (
    <picture className={['brand-logo-picture', themeClass].filter(Boolean).join(' ')}>
      <source
        media="(max-width: 359px)"
        srcSet={variant === 'white' ? '/brand/isotype-white.svg' : assets.isotype.src}
        width={assets.isotype.width}
        height={assets.isotype.height}
      />
      {image}
    </picture>
  );
}
