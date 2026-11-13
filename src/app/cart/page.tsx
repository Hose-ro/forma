import { CartContents } from '@/components/cart';
import { NO_INDEX } from '@/lib/seo';
export const metadata={title:'Tu bolsa',robots:NO_INDEX};
export default function Page(){return <div className="page"><div className="page-intro"><p className="eyebrow">Una selección muy tuya</p><h1>Tu <i>bolsa.</i></h1></div><CartContents/></div>}
