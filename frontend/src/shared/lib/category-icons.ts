import {
  Baby,
  Banknote,
  Briefcase,
  Car,
  Coffee,
  Dumbbell,
  Ellipsis,
  Gift,
  GraduationCap,
  HeartPulse,
  House,
  Landmark,
  Laptop,
  PartyPopper,
  PawPrint,
  PiggyBank,
  Plane,
  Plus,
  Repeat,
  Shirt,
  ShoppingCart,
  Tag,
  TrendingUp,
  Utensils,
  Wallet,
} from '@lucide/vue'
import type { Component } from 'vue'

/** The icons offered for categories, by Lucide name (includes every default category icon). */
export const CATEGORY_ICONS: Record<string, Component> = {
  'shopping-cart': ShoppingCart,
  house: House,
  car: Car,
  utensils: Utensils,
  'heart-pulse': HeartPulse,
  'party-popper': PartyPopper,
  repeat: Repeat,
  'graduation-cap': GraduationCap,
  ellipsis: Ellipsis,
  banknote: Banknote,
  laptop: Laptop,
  'trending-up': TrendingUp,
  plus: Plus,
  wallet: Wallet,
  'piggy-bank': PiggyBank,
  landmark: Landmark,
  plane: Plane,
  gift: Gift,
  shirt: Shirt,
  coffee: Coffee,
  dumbbell: Dumbbell,
  baby: Baby,
  'paw-print': PawPrint,
  briefcase: Briefcase,
}

export const CATEGORY_ICON_NAMES = Object.keys(CATEGORY_ICONS)

/** Icon for a stored name; unknown names fall back to a generic tag. */
export function categoryIcon(name: string): Component {
  return CATEGORY_ICONS[name] ?? Tag
}
