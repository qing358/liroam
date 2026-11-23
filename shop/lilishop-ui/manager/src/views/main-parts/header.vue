<template>
  <div>
    <el-row class="header">
      <img alt="平台 Logo" :src="domainLogo" class="logo" />
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from "vue";
import { loadSiteSettings } from "@/composables/useSiteSettings";
import defaultLogo from "@/assets/liroam-horizontal-ink.svg";

const domainLogo = ref(defaultLogo);

function applyFavicon(href) {
  if (!href) return;
  let link =
    document.querySelector("link[rel*='icon']") ||
    document.createElement("link");
  link.type = "image/x-icon";
  link.href = href;
  link.rel = "shortcut icon";
  document.getElementsByTagName("head")[0].appendChild(link);
}

function getSite() {
  loadSiteSettings().then((data) => {
    if (data) {
      const { domainLogo: logo, domainIcon, siteName, website } = data;
      domainLogo.value = logo || defaultLogo;
      const expirationTime = new Date().setHours(new Date().getHours() + 1);
      localStorage.setItem("icontitle_expiration_time", expirationTime);
      localStorage.setItem("icon", logo || "");
      localStorage.setItem("domainIcon", domainIcon || "");
      localStorage.setItem("title", siteName || "");
      localStorage.setItem("website", website || "");
      applyFavicon(domainIcon || logo);
      if (siteName) {
        window.document.title = siteName + " - 运营后台";
      }
    }
  });
}

onMounted(() => {
  getSite();
});
</script>

<style lang="scss" scoped>
.header {
  margin-bottom: 6vh;
  text-align: center;
  display: flex;
  justify-content: center !important;
}
.logo {
  height: 64px;
  width: auto;
}
</style>
