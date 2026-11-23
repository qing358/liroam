<template>
  <div class="welcome-box">
    <div class="famous-box">
      <img src="~@/assets/image/chat.png" width="300" />
      <p>选择左侧会话，解答买家关于选卡、开卡与用量的问题</p>
    </div>
  </div>
</template>

<script setup>
</script>
<style lang="less" scoped>
.welcome-box {
  height: 100%;
  width: 100%;

  .famous-box {
    display: flex;
    justify-content: center;
    align-items: center;
    flex-direction: column;
    height: 100%;
    font-size: 24px;
    user-select: none;

    p {
      width: 100%;
      font-weight: 300;
      text-align: center;
      font-size: 15px;
      color: #b9b4b4;
      margin-top: -30px;
    }
  }
}
</style>
