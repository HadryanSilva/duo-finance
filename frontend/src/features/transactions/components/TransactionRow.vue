<script setup lang="ts">
import { ArrowLeftRight } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { TransactionResponse } from '@/shared/api/types'
import { swatchInkClass } from '@/shared/lib/contrast'
import ArchivedBadge from '@/shared/ui/ArchivedBadge.vue'
import AuthorAvatar from '@/shared/ui/AuthorAvatar.vue'
import CategoryIcon from '@/shared/ui/CategoryIcon.vue'
import MoneyText from '@/shared/ui/MoneyText.vue'

const props = defineProps<{ transaction: TransactionResponse }>()
const emit = defineEmits<{ edit: [] }>()

const { t } = useI18n()

const isTransfer = computed(() => props.transaction.kind === 'TRANSFER')
const category = computed(() => props.transaction.category)
const origin = computed(() => props.transaction.bankAccount)
const destination = computed(() => props.transaction.destinationBankAccount)

/** The description, or the category name (or "Transfer") when it is empty. */
const title = computed(
  () =>
    props.transaction.description ||
    category.value?.name ||
    (isTransfer.value ? t('transactions.kind.TRANSFER') : ''),
)
/** The category name shows again under the title only when a description took its place. */
const subtitleCategory = computed(() =>
  props.transaction.description && category.value ? category.value : null,
)
const titleIsCategory = computed(() => !props.transaction.description && category.value !== null)
</script>

<template>
  <li>
    <button
      type="button"
      class="flex w-full items-center gap-3 rounded-md px-3 py-3 text-left outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50"
      @click="emit('edit')"
    >
      <span
        v-if="category"
        class="flex size-9 shrink-0 items-center justify-center rounded-full"
        :class="swatchInkClass(category.color)"
        :style="{ backgroundColor: category.color }"
      >
        <!-- the color comes from the API as data, not from the design tokens -->
        <CategoryIcon :name="category.icon" />
      </span>
      <span
        v-else
        class="flex size-9 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground"
      >
        <ArrowLeftRight class="size-4" aria-hidden="true" />
      </span>

      <span class="flex min-w-0 flex-1 flex-col gap-1">
        <span class="flex min-w-0 flex-wrap items-center gap-2">
          <span class="truncate font-medium text-foreground">{{ title }}</span>
          <ArchivedBadge v-if="titleIsCategory && category?.archived" />
        </span>
        <span
          class="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted-foreground"
        >
          <template v-if="isTransfer">
            <span>{{
              t('transactions.transferRoute', {
                from: origin?.name ?? '',
                to: destination?.name ?? '',
              })
            }}</span>
            <ArchivedBadge v-if="origin?.archived || destination?.archived" />
          </template>
          <template v-else>
            <template v-if="subtitleCategory">
              <span>{{ subtitleCategory.name }}</span>
              <ArchivedBadge v-if="subtitleCategory.archived" />
            </template>
            <template v-if="origin">
              <span v-if="subtitleCategory" aria-hidden="true">·</span>
              <span>{{ origin.name }}</span>
              <ArchivedBadge v-if="origin.archived" />
            </template>
          </template>
        </span>
      </span>

      <AuthorAvatar :author="transaction.createdBy" />
      <MoneyText
        :cents="transaction.amountCents"
        :kind="transaction.kind"
        class="shrink-0 text-right font-medium"
      />
    </button>
  </li>
</template>
