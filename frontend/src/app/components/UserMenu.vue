<script setup lang="ts">
import { LogOut } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useSession } from '@/shared/session/session-store'
import { Avatar, AvatarFallback } from '@/shared/ui/avatar'
import { Button } from '@/shared/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/shared/ui/dropdown-menu'

const { t } = useI18n()
const session = useSession()
const name = computed(() => session.user?.displayName ?? '')
const initials = computed(() =>
  name.value
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase())
    .join(''),
)
</script>

<template>
  <DropdownMenu>
    <DropdownMenuTrigger as-child>
      <Button variant="ghost" class="gap-2 px-2">
        <Avatar class="size-7" aria-hidden="true">
          <AvatarFallback class="text-xs">{{ initials }}</AvatarFallback>
        </Avatar>
        <span class="sr-only text-sm sm:not-sr-only sm:max-w-40 sm:truncate">{{ name }}</span>
      </Button>
    </DropdownMenuTrigger>
    <DropdownMenuContent align="end" class="min-w-48">
      <DropdownMenuLabel class="truncate">{{ name }}</DropdownMenuLabel>
      <DropdownMenuSeparator />
      <DropdownMenuItem @select="session.logout()">
        <LogOut aria-hidden="true" />
        {{ t('common.logout') }}
      </DropdownMenuItem>
    </DropdownMenuContent>
  </DropdownMenu>
</template>
